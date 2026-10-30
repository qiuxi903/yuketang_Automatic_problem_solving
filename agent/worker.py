"""任务执行器：领取任务 → 扫码 → 逐卷作答 → 交卷 → 回传进度。

P0 单账号串行；多实例并发由 WORKER_COUNT 与 backend 的任务领取接口共同保证，
同一任务只会被一个 Worker 领到。
"""

import logging
import queue
import threading
import time

import requests

import config
from backend_client import BackendClient, TaskGone
from collector_client import CollectorClient
from matcher_client import MatcherClient
from ykt_driver import AbortTask, Driver, LoginRequired

log = logging.getLogger("agent.worker")

# 雨课堂账号占用注册表：账号名 → (任务号, 工人名)。
# 同账号铁律的最后防线：即使调度器漏判（如两个新任务扫了同一个号），
# 登录成功后也会在此检测并立即停止后来者。
_ACCOUNT_USE = {}
_ACCOUNT_LOCK = threading.Lock()

# 多选答案在答案库/校验里用「；」连接多个选项（与 ykt_driver.ANSWER_SEP 一致）
_ANSWER_SEP = "；"


def _picked_texts(options, pick):
    """把单选索引（int）或多选索引（list[int]）转成命中的选项文本列表。"""
    picks = list(pick) if isinstance(pick, (list, tuple)) else (
        [pick] if isinstance(pick, int) and pick >= 0 else [])
    return [options[j] for j in picks if isinstance(j, int) and 0 <= j < len(options)]


def _pick_count(pick):
    """一道题预期勾选的选项数（用于与页面已勾选数核对）。"""
    if isinstance(pick, (list, tuple)):
        return len([x for x in pick if isinstance(x, int) and x >= 0])
    return 1 if isinstance(pick, int) and pick >= 0 else 0


def claim_account(account, task_id, worker_name, force=False):
    """登记账号占用。返回 (False, 占用者信息) 表示已被其它任务占用。

    force=True：覆盖过期登记（占用者经后端运行列表核实已不在执行）。
    """
    with _ACCOUNT_LOCK:
        holder = _ACCOUNT_USE.get(account)
        if holder and holder[0] != task_id and not force:
            return False, holder
        _ACCOUNT_USE[account] = (task_id, worker_name)
        return True, None


def release_account(account, task_id):
    with _ACCOUNT_LOCK:
        cur = _ACCOUNT_USE.get(account)
        if cur and cur[0] == task_id:
            _ACCOUNT_USE.pop(account, None)


class Worker:
    """单执行工人：独占一个浏览器，同一时刻只跑一个任务（即一个雨课堂账号）。

    多账号并行 = 多个 Worker；任务由 Dispatcher 派发（见文件末尾）。
    工人保持浏览器常驻并记住当前登录的雨课堂账号（ykt_name）：
    - 同账号续做（task.reuseYkt 命中）→ 免扫码直接开做；
    - 其它任务 → reset_context 重建上下文，从机制上杜绝账号串号。
    """

    def __init__(self, name="w0"):
        self.name = name
        self.backend = BackendClient()
        self.matcher = MatcherClient()
        self.collector = CollectorClient()
        self.queue = queue.Queue()
        self.busy = False
        self.ykt_name = None        # 当前浏览器登录的雨课堂账号名
        self._frame_at = 0.0
        # 阶段播报：进度长时间不动时，用户能看到 Agent 正在干什么、已持续多久
        self._stage_text = ""
        self._stage_at = 0.0
        self._activity_at = 0.0
        # 中止标志：心跳发现任务被暂停/中断时置位，执行流在安全点检查并停手
        self._abort = threading.Event()
        # 浏览器跨任务常驻复用：省去每个任务冷启动 Chrome 的数秒等待，二维码秒出
        self.driver = None
        self.current_task = None      # 正在执行的任务（调度器据此做同账号排队）

    def _stage(self, task_id, text, announce=True):
        """切换当前阶段；announce=True 时立即推送一条 message。"""
        self._stage_text = text
        self._stage_at = time.time()
        self._activity_at = self._stage_at
        if announce:
            try:
                self.backend.post_progress(task_id, message=text)
            except Exception:
                pass

    def _touch(self):
        """有真实进展（如逐题作答）时刷新活跃时间，心跳据此保持安静。"""
        self._activity_at = time.time()

    def _tick(self, driver, task_id):
        """长等待期间的心跳回调：推一帧实时画面（按 FRAME_INTERVAL 节流）+ 检查中止。

        交卷/等结果页/翻页这些阶段原本不推帧，前端预览会卡住不动；
        由 driver 的轮询循环每 300-400ms 回调一次，预览就一直是活的。
        同时在这里检查中止标志——用户点暂停/中断后，最迟 0.4s 内停手，
        不必等到下一个「安全点」（以前要等交卷/采集整段跑完，最长几十秒）。
        """
        if self._abort.is_set():
            raise AbortTask()
        self._touch()
        self._push_frame(driver, task_id)

    def _sleep_frames(self, driver, task_id, seconds):
        """带推帧的等待：替代裸 time.sleep，卷间间隔也不再让预览冻结。"""
        deadline = time.time() + seconds
        while time.time() < deadline:
            if self._abort.is_set():
                raise AbortTask()
            self._tick(driver, task_id)
            time.sleep(min(0.4, max(0.05, deadline - time.time())))

    def _heartbeat(self, task_id, stop_evt):
        """心跳：①播报长耗时阶段；②轮询任务状态，被暂停/中断时置中止标志。

        播报只在超过 4.5s 没有任何进展推送时才发声——正常逐题作答期间保持安静；
        状态轮询让「暂停」真正生效：2s 一轮，配合执行流里的中止检查快速停手。
        """
        while not stop_evt.wait(2):
            # 1) 状态巡检：PAUSED/CANCELLED（或已被后端结束）→ 请求中止
            try:
                decision = self.backend.batch_decision(task_id)
                status = decision.get("status")
                if status in ("PAUSED", "CANCELLED"):
                    log.info("[%s] 任务被用户%s，将在安全点停止", self.name,
                             "暂停" if status == "PAUSED" else "中断")
                    self._abort.set()
                elif status in ("SUCCESS", "FAILED"):
                    self._abort.set()
            except requests.HTTPError as exc:
                if getattr(exc, "response", None) is not None and exc.response.status_code == 404:
                    log.info("[%s] 任务 #%s 已被删除，请求中止", self.name, task_id)
                    self._abort.set()
            except Exception:
                pass
            if self._abort.is_set():
                return
            # 2) 长耗时阶段播报
            text = self._stage_text
            if not text:
                continue
            if time.time() - self._activity_at < 4.5:
                continue
            elapsed = int(time.time() - self._stage_at)
            try:
                self.backend.post_progress(task_id, message=f"{text}…已持续 {elapsed}s")
            except Exception:
                pass

    def _occupier_still_running(self, task_id):
        """占用登记里的任务是否真的还在运行（不在 → 登记过期，可接管）。"""
        try:
            tasks = self.backend.get_running_tasks()
            return any(t.get("taskId") == task_id for t in tasks or [])
        except Exception:
            return True  # 查询失败时保守处理：视为仍在运行

    def _wait_login_with_qr(self, driver, task_id):
        """等待扫码；期间每 ~12s 重截一次二维码回传。

        雨课堂二维码约 2 分钟过期，此前只回传一次——过期后前端只能拿到死码。
        周期重截后，前端「重新获取二维码」= 重拉会话即可拿到新图，无需重建会话。
        """
        deadline = time.time() + config.LOGIN_TIMEOUT
        posted_at = 0.0
        while time.time() < deadline:
            if self._abort.is_set():
                raise AbortTask()
            if driver.is_logged_in():
                return
            if time.time() - posted_at >= 12:
                try:
                    qr = driver.capture_qrcode()
                    if qr:
                        self.backend.post_qrcode(task_id, qr)
                        posted_at = time.time()
                except Exception:
                    pass
            driver.page.wait_for_timeout(1500)
        raise LoginRequired("扫码登录超时")

    def _ensure_driver(self):
        if self.driver is not None:
            return self.driver
        # 每个工人一个持久化 profile：登录态跨重启保留，同账号续做免扫码
        driver = Driver(profile=self.name)
        driver.start()
        self.driver = driver
        return driver

    def _close_driver(self):
        if self.driver is not None:
            try:
                self.driver.stop()
            except Exception:
                pass
            self.driver = None

    def feed(self, task):
        """调度器派发任务到本工人。"""
        self.queue.put(task)

    def idle(self):
        return not self.busy

    def _prewarm(self):
        """预热浏览器：失败自动重试一次（Chrome 冷启动偶发竞态）。

        预热时顺便识别 profile 里是否保留着登录态：有就把当前账号记下来，
        之后同账号任务直接沿用（重启 Agent 也不用重新扫码）。
        """
        for attempt in (1, 2):
            try:
                driver = self._ensure_driver()
                driver.page.goto(config.YKT_INDEX, wait_until="domcontentloaded", timeout=30000)
                name = driver.ykt_name()
                if name:
                    self.ykt_name = name
                    log.info("[%s] 浏览器已预热，profile 保留了登录态：%s（同账号任务免扫码）",
                             self.name, name)
                else:
                    log.info("[%s] 浏览器已预热（雨课堂入口页就绪），任务到达后可立即出二维码", self.name)
                return
            except Exception as exc:
                log.warning("[%s] 浏览器预热失败（第 %d 次）：%s", self.name, attempt, exc)
                self._close_driver()
                time.sleep(3)

    def run_forever(self):
        log.info("[%s] 执行工人就绪（独占浏览器，当前账号：%s）", self.name, self.ykt_name or "无")
        self._prewarm()
        while True:
            task = self.queue.get()
            self.busy = True
            self.current_task = task
            log.info("[%s] 开始任务 #%s batch=%s mode=%s",
                     self.name, task.get("taskId"), task.get("batch"), task.get("mode"))
            try:
                self.run_task(task)
            except AbortTask:
                # 用户暂停/中断：后端状态已是 PAUSED/CANCELLED，绝不能按「任务失败」回传
                # （否则 FAILED 会覆盖暂停态，任务变成不可恢复）
                log.info("[%s] 任务 #%s 已在安全点停止（暂停/中断），保持后端状态",
                         self.name, task.get("taskId"))
            except Exception as exc:
                log.exception("[%s] 任务执行异常", self.name)
                try:
                    self._safe_finish(task, "FAILED", summary=str(exc))
                except Exception:
                    pass
                self._close_driver()
                self.ykt_name = None
            finally:
                self.busy = False
                self.current_task = None

    # —— 单任务 ——

    def run_task(self, task):
        task_id = task["taskId"]
        mode = task.get("mode", "answer")
        ykt_name = None  # 登录成功后记录；finally 释放占用时可能尚未登录

        driver = self._ensure_driver()
        driver.submit_forbidden = False
        self._abort = threading.Event()  # 每个任务重置中止标志
        stop_hb = threading.Event()
        threading.Thread(target=self._heartbeat, args=(task_id, stop_hb), daemon=True).start()
        try:
            # 账号隔离：同账号续做才沿用登录；否则强制重建上下文（清 Cookie），杜绝串号
            reuse_ykt = task.get("reuseYkt")
            if reuse_ykt and self.ykt_name == reuse_ykt:
                log.info("[%s] 沿用已登录账号 %s（免扫码）", self.name, reuse_ykt)
            else:
                if self.ykt_name:
                    log.info("[%s] 切换账号：%s → 新账号（重建浏览器上下文）", self.name, self.ykt_name)
                self._stage(task_id, "准备全新浏览器上下文（账号隔离）")
                driver.reset_context()
                self.ykt_name = None
                log.info("[%s] 上下文已重建（旧登录态已清除）", self.name)
            self._stage(task_id, "打开雨课堂登录页")
            qr = driver.open_and_capture_qr()
            if qr:
                self.backend.post_qrcode(task_id, qr)
                self.backend.post_session_status(task_id, "WAITING_SCAN")
                log.info("[%s] 二维码已回传，等待扫码", self.name)
            self._push_frame(driver, task_id, force=True)
            self._stage(task_id, "等待扫码登录")
            self._wait_login_with_qr(driver, task_id)
            ykt_name = driver.ykt_name()
            self.ykt_name = ykt_name or self.ykt_name
            # 同账号铁律最后防线：该雨课堂账号已被其它在跑任务占用 → 立即停止本任务
            if ykt_name:
                ok, holder = claim_account(ykt_name, task_id, self.name)
                if not ok:
                    # 占用者可能早已结束（人做完直接关了页面、任务被删/暂停）而登记未释放。
                    # 自救：查后端运行列表，占用任务不在 RUNNING → 强制接管本账号。
                    if not self._occupier_still_running(holder[0]):
                        log.warning("[%s] 账号 %s 的占用登记已过期（任务 #%s 不在运行列表），强制接管",
                                    self.name, ykt_name, holder[0])
                        claim_account(ykt_name, task_id, self.name, force=True)
                    else:
                        msg = (f"雨课堂账号 {ykt_name} 正在任务 #{holder[0]} 中执行，"
                               "同一账号不能同时做题；请等那个任务结束或暂停后再试")
                        log.warning("[%s] %s", self.name, msg)
                        self.backend.post_progress(task_id, message=msg)
                        raise RuntimeError(msg)
            self.backend.post_session_status(task_id, "SCANNED", ykt_name)
            log.info("[%s] 扫码登录成功：%s", self.name, ykt_name)
            self._push_frame(driver, task_id, force=True)

            # 课程：优先用配置指定，未配置则自动进入「我听的课」首门课
            self._stage(task_id, "进入课程")
            try:
                course = driver.open_course(config.COURSE_NAME or None)
            except Exception as exc:
                log.warning("[%s] 进入课程失败：%s", self.name, exc)
                course = config.COURSE_NAME or None
            self._push_frame(driver, task_id, force=True)

            self._stage(task_id, "读取账号作业清单")
            entries = driver.list_papers(on_tick=lambda: self._tick(driver, task_id))
            log.info("[%s] 课程 %s 账号实际作业 %d 套：%s",
                     self.name, course, len(entries), [e.get("name") for e in entries])

            # 批次后置 + 模式后置：未预先指定批次时，回传作业清单并等待用户确认批次与模式
            batch = task.get("batch")
            if not batch:
                batch, mode = self._wait_batch(task, task_id, entries, driver, course, mode)
            if not batch:
                raise RuntimeError("未获得批次确认，任务终止")

            # 采集模式：置硬门禁，禁止任何交卷动作（避免重复消耗作答次数）
            driver.submit_forbidden = (mode == "collect")

            papers = self._papers_of_batch(batch)
            plan = self._match_entries(papers, entries)
            # 重做任务：只做指定卷子（来自「取答案未做」的一键重做或清单勾选），其余一律跳过
            redo = [r for r in (task.get("redoPapers") or []) if r]
            if redo:
                plan = [p for p in plan if any(r == p["name"] or r in p["name"] or p["name"] in r for r in redo)]
                # 新出的卷子可能还没进答案库批次：从账号作业里补齐（先做一遍 → 采答案自动入库）
                in_plan = {p["name"] for p in plan}
                for e in entries:
                    ename = e.get("name") or ""
                    if not any(r == ename or r in ename or ename in r for r in redo):
                        continue
                    if any(ename in n or n in ename for n in in_plan):
                        continue
                    plan.append({
                        "name": ename,
                        "examId": e.get("examId"),
                        "courseId": e.get("courseId"),
                        "entryText": e.get("entryText"),
                        "answered": bool(e.get("answered")),
                        "entryStatus": e.get("status"),
                        "newPaper": True,  # 批次外新卷：本轮可能无库内答案，交卷后采答案入库
                    })
                    in_plan.add(ename)
                log.info("[%s] 重做任务：本次只重做 %d 套：%s",
                         self.name, len(plan), [p["name"] for p in plan])

            # 续做（暂停后恢复 / 继续作答）：已完成（已交卷/已复用/已采集）的卷子跳过，
            # 不重复走一遍——否则恢复任务会从第一套重新翻结果页，看起来像从头再来
            if not redo:
                done_names = {
                    it.get("paper") for it in (task.get("items") or [])
                    if it.get("paper") and it.get("status") in ("SUBMITTED", "REUSED", "COLLECTED")
                }
                if done_names:
                    before = len(plan)
                    plan = [
                        p for p in plan
                        if not any(d == p["name"] or d in p["name"] or p["name"] in d for d in done_names)
                    ]
                    skipped = before - len(plan)
                    if skipped:
                        log.info("[%s] 续做：跳过已完成 %d 套，继续处理剩余 %d 套",
                                 self.name, skipped, len(plan))
                        self.backend.post_progress(
                            task_id, message=f"续做：跳过已完成 {skipped} 套，继续处理剩余 {len(plan)} 套")

            # 未勾选任何卷子（「做全部作业」）：把账号里未入答案库的新卷子也纳入计划，
            # 否则新卷子会被静默跳过（用户以为「全部作业」包含它们）
            if not redo:
                lib_names = self._lib_names()
                in_plan = {p["name"] for p in plan}
                fresh = []
                for e in entries:
                    ename = e.get("name") or ""
                    if not ename or any(ename in n or n in ename for n in in_plan):
                        continue
                    if self._in_lib(lib_names, ename):
                        continue  # 已在答案库（含其它批次）→ 不算新卷子
                    fresh.append(e)
                if fresh:
                    names = "、".join(e["name"] for e in fresh[:8])
                    more = " 等" if len(fresh) > 8 else ""
                    if mode == "collect":
                        # 采集模式：新卷子没做过，结果页取不到答案，如实说明并引导去做题
                        self.backend.post_progress(
                            task_id,
                            message=f"另有 {len(fresh)} 套新卷子尚未作答，无法取答案（{names}{more}）；"
                                    "请用「做题」处理，做完答案会自动入库")
                    else:
                        for e in fresh:
                            plan.append({
                                "name": e["name"],
                                "examId": e.get("examId"),
                                "courseId": e.get("courseId"),
                                "entryText": e.get("entryText"),
                                "answered": bool(e.get("answered")),
                                "entryStatus": e.get("status"),
                                "newPaper": True,  # 批次外新卷：库内暂无答案，交卷后采答案入库
                            })
                        log.info("[%s] 检测到 %d 套未入答案库的新卷子，一并处理：%s",
                                 self.name, len(fresh), [e["name"] for e in fresh])
                        self.backend.post_progress(
                            task_id,
                            message=f"检测到 {len(fresh)} 套新卷子（未入答案库），将一并作答并采集答案：{names}{more}")
            if mode == "collect":
                answered_cnt = len([p for p in plan if p.get("answered")])
                log.info("[%s] 批次 %s 采集模式：待处理 %d 套，其中已交卷 %d 套（仅读结果页取答案）",
                         self.name, batch, len(plan), answered_cnt)
            else:
                log.info("[%s] 批次 %s 待作答 %d 套：%s",
                         self.name, batch, len(plan), [p["name"] for p in plan])

            stats = {"fetched": 0, "reused": 0, "skipped": 0, "answered": 0, "failed": 0}
            aborted = False
            for i, paper in enumerate(plan):
                if self._abort.is_set():
                    aborted = True
                    rest = len(plan) - i
                    break
                if i > 0:
                    # 上一卷结束停在结果页：先回学习日志，下一卷的 open_paper 才点得到
                    # want_name：滚到目标卷出现即停，不必等整页懒加载
                    self._stage(task_id, "返回试卷列表")
                    try:
                        driver.back_to_paper_list(
                            want_name=paper.get("name"),
                            on_tick=lambda: self._tick(driver, task_id))
                    except Exception as exc:
                        log.warning("[%s] 回学习日志失败：%s", self.name, exc)
                try:
                    result = self._answer_one(driver, task, batch, paper, mode)
                except AbortTask:
                    aborted = True
                    rest = len(plan) - i
                    break
                stats[result] = stats.get(result, 0) + 1
                if result == "failed":
                    log.warning("[%s] 试卷 %s 未完成", self.name, paper["name"])
                self._push_frame(driver, task_id, force=True)

            if aborted:
                # 已暂停/中断：后端状态已是 PAUSED/CANCELLED，这里不覆盖，只补一条说明
                log.info("[%s] 任务在安全点停止，剩余 %d 套未处理", self.name, rest)
                try:
                    self.backend.post_progress(
                        task_id, message=f"已在安全点暂停，剩余 {rest} 套未处理；点「继续做题」可接着做")
                except Exception:
                    pass
                return

            driver.back_to_index()
            self._push_frame(driver, task_id, force=True)
            self._stage_text = ""  # 收尾：停用心跳播报，避免覆盖总结
            if mode == "collect":
                summary = (f"只取答案完成：新采集 {stats['fetched']} 套，直接复用 {stats['reused']} 套"
                           f"，未交卷 {stats['skipped']} 套（先做题后才能取），失败 {stats['failed']} 套")
            else:
                summary = (f"全部试卷处理完成：作答 {stats['answered']} 套，取答案 {stats['fetched']} 套"
                           f"，复用 {stats['reused']} 套，失败 {stats['failed']} 套")
            self._safe_finish(task, "SUCCESS", summary=summary)
        except AbortTask:
            # 暂停/中断发生在任何阶段（扫码等待、试卷列表扫描、批次确认…）都在此收口：
            # 后端状态已是 PAUSED/CANCELLED，只补一条说明，绝不覆盖状态
            log.info("[%s] 任务 #%s 在安全点停止（暂停/中断）", self.name, task_id)
            try:
                self.backend.post_progress(
                    task_id, message="已在安全点停止；点「继续做题」可接着处理剩余卷子")
            except Exception:
                pass
        except TaskGone:
            log.info("[%s] 任务 #%s 已被用户删除，停止执行（浏览器保留）", self.name, task_id)
        except LoginRequired as exc:
            self._stage_text = ""
            self.backend.post_session_status(task_id, "EXPIRED")
            self._safe_finish(task, "FAILED", summary="登录超时：%s" % exc)
        finally:
            stop_hb.set()
            if ykt_name:
                release_account(ykt_name, task_id)
            # 浏览器保留复用（下个任务免冷启动）；页面异常兜底回首页，回不去则重建
            try:
                driver.back_to_index()
            except Exception:
                self._close_driver()

    def _wait_batch(self, task, task_id, entries, driver=None, course=None, mode=None):
        """回传账号作业清单，轮询等待用户确认批次与模式；超时或任务结束返回 (None, mode)。

        等待期间持续回传当前页面截图，方便用户在浏览器模式里看到雨课堂原页面。
        """
        try:
            self.backend.post_papers(task_id, entries, course)
        except Exception as exc:
            log.warning("[%s] 回传作业清单失败：%s", self.name, exc)
            return None, mode
        log.info("[%s] 作业清单已回传，等待确认批次与模式", self.name)

        deadline = time.time() + config.BATCH_WAIT_TIMEOUT
        while time.time() < deadline:
            if self._abort.is_set():
                raise AbortTask()  # 等待批次确认期间被暂停/中断：立刻收口
            if driver is not None:
                self._push_frame(driver, task_id)
            try:
                decision = self.backend.batch_decision(task_id)
            except Exception as exc:
                log.warning("[%s] 查询批次决策失败：%s", self.name, exc)
                time.sleep(config.POLL_INTERVAL)
                continue
            if decision.get("batch"):
                confirmed_mode = decision.get("mode") or mode
                # 重取任务快照：用户在确认时勾选的卷子清单（redoPapers）等
                # 只存在于最新快照里，领取时的旧快照不含这些，必须同步
                refreshed = False
                try:
                    for t in self.backend.get_running_tasks():
                        if t.get("taskId") == task_id:
                            task.update(t)
                            refreshed = True
                            log.info("[%s] 任务快照已刷新（勾选 %s 套）",
                                     self.name, len(t.get("redoPapers") or []))
                            break
                except Exception as exc:
                    log.warning("[%s] 任务快照刷新失败：%s", self.name, exc)
                if not refreshed:
                    log.warning("[%s] 任务快照刷新未命中（任务可能已不在运行列表）", self.name)
                log.info("[%s] 批次已确认：%s，模式：%s",
                         self.name, decision["batch"], confirmed_mode)
                return decision["batch"], confirmed_mode
            if decision.get("status") in ("FAILED", "SUCCESS"):
                log.warning("[%s] 任务已结束，停止等待批次", self.name)
                return None, mode
            time.sleep(config.POLL_INTERVAL)
        log.warning("[%s] 等待批次确认超时（%.0fs）", self.name, config.BATCH_WAIT_TIMEOUT)
        return None, mode

    def _answer_one(self, driver, task, batch, paper, mode):
        """单卷调度：按「是否已交卷 + 答案库是否已有」决定 复用 / 只读取答案 / 提示先作答 / 正常作答。

        规则（用户口径）：
        1) 卷子交过 → 直接获取答案，不再次交卷（不重复消耗作答次数）；
        2) 没交过 → 不能获取答案，提示先去做题，做完题后可获取；
        3) 该答案别的账号已采集过 → 直接复用，不重复获取。

        返回结果分类（reused/skipped/fetched/answered/failed），供任务收尾汇总。
        """
        task_id = task["taskId"]
        name = paper["name"]
        exam_id = paper.get("examId")
        answered = bool(paper.get("answered"))
        # 答案实际所在批次（可能 ≠ 任务批次：新卷答案会进「当天日期」批次）
        lib_batch = self._resolve_lib_batch(batch, name)

        # 重做清单里的卷子：answer 模式下无视复用/已交卷规则直接重做（追求好成绩）；
        # collect 模式下不得强推交卷（门禁会拦），走正常采集分流
        redo = [r for r in (task.get("redoPapers") or []) if r]
        if mode == "answer" and any(r == name or r in name or name in r for r in redo):
            log.info("[%s] %s 在重做清单，直接重做（答案批次 %s）", self.name, name, lib_batch or "新卷子")
            # 库里有答案就用库所在批次，否则用任务批次（新卷子：build_idx 会返回无答案，走兜底）
            return "answered" if self._answer_and_submit(driver, task, lib_batch or batch, paper, mode) else "failed"

        # 规则3：答案库已有（含别的账号采集的）→ 直接复用，不抓取、不作答、不交卷。
        # 但仅当库内答案「完整」时复用：结果页偶尔有题不显示正确标记（如 1915 第 19 题），
        # 库里缺题时直接复用会让缺口永远补不上——此时重新采集补缺。
        if lib_batch and (mode == "collect" or answered):
            lib_count = self._lib_answer_count(lib_batch, name)
            need = paper.get("questionCount")
            incomplete = self._lib_incomplete(lib_batch, name) or (
                need and lib_count < need)
            if incomplete:
                log.info("[%s] %s 答案库该卷不完整（%s），重新采集补缺",
                         self.name, name, f"{lib_count}/{need} 题" if need else "题号断档")
                self.backend.post_progress(
                    task_id, name, exam_id,
                    message=f"答案库该卷不完整（{lib_count} 题），重新读取结果页补齐")
            else:
                log.info("[%s] %s 答案库已有（批次 %s），直接复用", self.name, name, lib_batch)
                self.backend.post_progress(task_id, name, exam_id, status="REUSED",
                                           message="答案库已有该卷答案，直接复用（未重复获取）")
                self._reuse_from_lib(task_id, lib_batch, name, exam_id)
                return "reused"

        # 规则2：未交卷 + 采集模式 → 不能取答案，提示先去做题
        if mode == "collect" and not answered:
            log.info("[%s] %s 尚未作答，采集模式下跳过", self.name, name)
            self.backend.post_progress(task_id, name, exam_id, status="SKIPPED",
                                       message="该试卷尚未作答，无法获取答案；请先完成作答，完成后再采集")
            return "skipped"

        # 规则1：已交卷 → 只读结果页取答案，不进入答题、不交卷
        if answered:
            return "fetched" if self._collect_from_result(driver, task, batch, paper) else "failed"

        # 未交卷 + 自动作答模式 → 正常 进入/作答/交卷
        return "answered" if self._answer_and_submit(driver, task, batch, paper, mode) else "failed"

    def _answer_and_submit(self, driver, task, batch, paper, mode):
        task_id = task["taskId"]
        name = paper["name"]
        exam_id = paper.get("examId")
        self._stage(task_id, f"进入卷子 {name}", announce=False)
        self.backend.post_progress(task_id, name, exam_id,
                                   status="ANSWERING", message="进入 %s" % name)
        self._touch()
        tick = lambda: self._tick(driver, task_id)
        try:
            cid, eid = paper.get("courseId"), paper.get("examId")
            if cid and eid:
                driver.open_exam(cid, eid, on_tick=tick)
            else:
                cid, eid = driver.open_paper(name, on_tick=tick)
            if not eid:
                raise RuntimeError("未能定位试卷入口：%s" % name)
            exam_id = eid

            # 防重复保险：详情页若显示已交卷，区分「有人做出过答案」与「0 分空白卷」
            state = driver.exam_entry_state()
            if state == "done":
                page_score = driver.exam_score_on_page()
                lib_batch = self._resolve_lib_batch(batch, name)
                has_answers = bool(
                    lib_batch and (self.matcher.paper(lib_batch, name) or {}).get("questions")
                )
                # 重做清单里的卷子：用户明确点「再做一遍」刷分，已有分数也重做
                forced_redo = mode == "answer" and any(
                    r == name or r in name or name in r
                    for r in (task.get("redoPapers") or [])
                )
                if not (forced_redo or (config.ZERO_REDO and page_score == 0 and not has_answers and mode == "answer")):
                    # 有分数（有人做出过/已复用）或非 answer 模式 → 不重复作答，只采集答案
                    log.info("[%s] %s 雨课堂显示已交卷（页面得分 %s），跳过作答并采集答案",
                             self.name, name, page_score)
                    self.backend.post_progress(
                        task_id, name, exam_id, status="COLLECTED",
                        message="已经做过（雨课堂显示已交卷），不再重复作答；直接采集答案入库")
                    self._stage(task_id, f"{name}：已经做过，采集答案入库", announce=False)
                    try:
                        ok = driver.open_result(exam_id, on_tick=tick)
                        if not ok and cid:
                            driver.open_exam(cid, exam_id, on_tick=tick)
                            ok = driver.open_result_from_detail(on_tick=tick)
                        got = self._collect(driver, task_id, batch, name, exam_id, on_tick=tick) if ok else 0
                    except (AbortTask, TaskGone):
                        raise
                    except Exception as exc:
                        log.warning("[%s] %s 采集已有答案失败：%s", self.name, name, exc)
                        got = 0
                    self._sleep_frames(driver, task_id, config.PAPER_GAP)
                    return "fetched" if got else "skipped"
                # 0 分空白卷且库里也无答案 → 自动「再次答题」重做一遍：
                # 交卷后的结果页会显示正确答案，采集入库（消耗 1 次作答机会）
                log.info("[%s] %s 已交卷但 0 分（空白卷）：自动重做一遍以采集答案（消耗 1 次作答）",
                         self.name, name)
                self.backend.post_progress(
                    task_id, name, exam_id, status="ANSWERING",
                    message="已交卷但 0 分（空白卷）：自动重做一遍，交卷后即可采集答案入库")

            if self._abort.is_set():
                raise AbortTask()  # 用户已暂停/中断：不再进入答题页
            self._stage(task_id, f"{name}：进入答题页")
            if not driver.enter_exam(on_tick=tick):
                raise RuntimeError("未找到开始答题按钮")
            if not driver.wait_exam_ready(on_tick=tick):
                raise RuntimeError("答题页未就绪")
            self._push_frame(driver, task_id, force=True)

            self._stage(task_id, f"{name}：抓取题目")
            questions = driver.scrape_questions()
            self._push_questions(task_id, name, exam_id, questions)
            self._stage(task_id, f"{name}：匹配答案库")
            built = self.matcher.build_idx(batch, name, questions)
            idx = built.get("idx", [])
            cross = built.get("crossMatched") or []
            if cross:
                log.info("[%s] %s 跨卷同题命中 %d/%d 题（借用库内其它试卷的同题答案）",
                         self.name, name, len(cross), len(questions))
            if not idx and questions:
                # 兜底：matcher 返回异常（无 idx 字段）时，按「全部未匹配」处理，
                # 让兜底补答与交卷后采集照常进行
                idx = [-1] * len(questions)
            if not built.get("paperInLib", True) and questions:
                # 新卷子（答案库没有该卷）：先跨卷同题匹配，剩余的交卷后采集入库
                if cross:
                    msg = (f"新卷子：答案库其它试卷命中 {len(cross)}/{len(questions)} 题（同类题目复用），"
                           "其余交卷后采集入库")
                else:
                    msg = (f"新卷子：答案库暂无该卷，{len(questions)} 题将尝试兜底 API"
                           "（未配置则交卷后采集答案入库）")
                self.backend.post_progress(task_id, name, exam_id, message=msg)
            if built.get("lowConfidence"):
                log.warning("[%s] %s 低置信题 %d 道", self.name, name, len(built["lowConfidence"]))
            # 新题型（填空/简答等无选项题）：如实告知用户，自动跳过不点选
            noopts = built.get("noOptions") or []
            if noopts:
                qs = "、".join(str(x.get("q")) for x in noopts[:12])
                more = " 等" if len(noopts) > 12 else ""
                self.backend.post_progress(
                    task_id, name, exam_id,
                    message=f"第 {qs}{more} 题没有选项（填空/主观题等新题型），本平台暂不支持自动作答，已跳过")

            self._stage(task_id, f"{name}：兜底补答")
            self._fill_by_fallback(batch, name, questions, idx)

            # 逐题回报：前端「题号进度」实时跟进，不再一卷结束才跳一格
            self._stage(task_id, f"{name}：逐题作答", announce=False)
            total_q = len(questions)

            def _report(i):
                self._touch()
                if self._abort.is_set():
                    raise AbortTask()  # 用户暂停：停止点击，半途的作答雨课堂会保留
                self.backend.post_progress(task_id, name, exam_id,
                                           seq=min(i + 1, total_q), total=total_q)
                self._push_frame(driver, task_id)

            driver.click_answers(idx, on_progress=_report)
            self._push_frame(driver, task_id, force=True)
            # idx 每项是 int（单选）或 list[int]（多选）；拼出已选选项文本供校验。
            # 未作答的题保留空串占位，保证下标与题号对齐（否则校验结果整体错位）
            selected = []
            for i in range(min(len(questions), len(idx))):
                texts = _picked_texts(questions[i].get("options", []), idx[i])
                selected.append("；".join(texts) if texts else "")
            checked = driver.checked_count()
            expected_checked = sum(_pick_count(x) for x in idx)
            if checked < expected_checked:
                log.warning("[%s] %s 已勾选 %d < 预期 %d，重试点击",
                            self.name, name, checked, expected_checked)
                driver.click_answers(idx)
                self._push_frame(driver, task_id, force=True)

            # 只有答案库里确有该卷时才做一致性校验（新卷子没有基准答案，校验无意义）
            if self._lib_has_answers(batch, name):
                report = self.matcher.verify(batch, name, selected)
                if report.get("bad"):
                    log.warning("[%s] %s 校验差异 %d 处", self.name, name, report["bad"])

            if self._abort.is_set():
                raise AbortTask()  # 用户已暂停/中断：不交卷（半途作答雨课堂会保留，恢复后可继续）
            self._stage(task_id, f"{name}：交卷中")
            driver.submit(on_tick=tick)
            self._push_frame(driver, task_id, force=True)
            self._stage(task_id, f"{name}：读取成绩")
            score = driver.read_score(on_tick=tick)
            # 交卷后雨课堂常停在封面页（/cover/）而不是结果页：先打开结果页再采集，
            # 否则会误报「未采到任何答案」
            if "/result/" not in (driver.page.url or ""):
                self._stage(task_id, f"{name}：打开结果页")
                driver.open_result(exam_id, on_tick=tick)
            self._push_frame(driver, task_id, force=True)
            self.backend.post_progress(task_id, name, exam_id,
                                       seq=len(questions), total=len(questions),
                                       score=score, status="SUBMITTED",
                                       message="已交卷 %s" % (score or ""))

            self._stage(task_id, f"{name}：采集答案入库")
            self._collect(driver, task_id, batch, name, exam_id, on_tick=tick)
            self._sleep_frames(driver, task_id, config.PAPER_GAP)
            return True
        except (AbortTask, TaskGone):
            raise  # 暂停/中断/任务删除：不算失败，交由 run_task 收尾
        except Exception as exc:
            log.exception("[%s] %s 作答失败", self.name, name)
            self.backend.post_progress(task_id, name, exam_id,
                                       status="FAILED", message=str(exc))
            return False

    def _collect_from_result(self, driver, task, batch, paper):
        """已交卷试卷：只读结果页取正确答案，不进入答题、不交卷。

        正确项（.dot-success）只在结果页可见；已交卷的卷子直接读结果页即可复用，
        从而避免「再次答题」再消耗一次作答次数。
        """
        task_id = task["taskId"]
        name = paper["name"]
        exam_id = paper.get("examId")
        self._stage(task_id, f"打开结果页 {name}", announce=False)
        self.backend.post_progress(task_id, name, exam_id, status="ANSWERING",
                                   message="读取已交卷结果页 %s" % name)
        self._touch()
        tick = lambda: self._tick(driver, task_id)
        try:
            if self._abort.is_set():
                raise AbortTask()
            course_id = paper.get("courseId")
            if not exam_id:
                course_id, exam_id = driver.open_paper(name, on_tick=tick)   # 落在考试详情页
            if not exam_id:
                raise RuntimeError("未能定位试卷 ID：%s" % name)

            ok = driver.open_result(exam_id, on_tick=tick)     # 直连结果页
            if not ok and course_id:
                driver.open_exam(course_id, exam_id, on_tick=tick)           # 兜底：详情页点「查看试卷」
                ok = driver.open_result_from_detail(on_tick=tick)
            if not ok:
                raise RuntimeError("结果页未就绪：%s" % name)
            self._push_frame(driver, task_id, force=True)

            self._stage(task_id, f"{name}：读取正确答案")
            try:
                score = driver.read_score(on_tick=tick)
            except Exception:
                score = None
            self.backend.post_progress(
                task_id, name, exam_id, score=score, status="COLLECTED",
                message="已交卷试卷，只读结果页取到答案（未重做）%s" % ("，得分 " + score if score else ""))

            self._collect(driver, task_id, batch, name, exam_id, on_tick=tick)
            self._sleep_frames(driver, task_id, config.PAPER_GAP)
            return True
        except (AbortTask, TaskGone):
            raise  # 暂停/中断/任务删除：不算失败
        except Exception as exc:
            log.exception("[%s] %s 读取结果页失败", self.name, name)
            self.backend.post_progress(task_id, name, exam_id,
                                       status="FAILED", message=str(exc))
            return False

    def _resolve_lib_batch(self, batch, name):
        """返回答案库中实际存有该卷答案的批次：优先任务��次，否则全库扫描；
        全库都没有该卷 → None（新卷子）。

        采集会把新卷子的答案写进「当天日期」的批次，任务绑定的批次未必是它所在——
        跨批次解析后，复用与作答都能用上全库最新的答案。
        """
        try:
            if (self.matcher.paper(batch, name) or {}).get("questions"):
                return batch
            for b in self.matcher.papers().get("batches", []):
                bn = b.get("batch")
                if not bn or bn == batch:
                    continue
                names = [p.get("name") for p in b.get("papers", []) if p.get("name")]
                if any(n == name or n in name or name in n for n in names):
                    if (self.matcher.paper(bn, name) or {}).get("questions"):
                        return bn
        except Exception:
            pass
        return None

    def _lib_names(self):
        """答案库里已有的全部卷名（跨批次，一次拉取），用于��速判断「新卷子」。"""
        names = set()
        try:
            for b in self.matcher.papers().get("batches", []):
                for p in b.get("papers", []):
                    n = p.get("name")
                    if n:
                        names.add(n)
        except Exception:
            pass
        return names

    @staticmethod
    def _in_lib(lib_names, name):
        """卷名是否已在答案库（宽松匹配，容忍名称前后缀差异）。"""
        return any(n == name or n in name or name in n for n in lib_names)

    def _lib_has_answers(self, batch, name):
        """答案库中是否已存在该卷答案（跨批次查找）。"""
        try:
            return bool((self.matcher.paper(self._resolve_lib_batch(batch, name), name) or {}).get("questions"))
        except Exception:
            return False

    def _lib_answer_count(self, batch, name):
        """答案库里该卷的答案条数（用于判断是否完整）。"""
        try:
            return int((self.matcher.paper(batch, name) or {}).get("questions") or 0)
        except Exception:
            return 0

    def _lib_incomplete(self, batch, name):
        """答案库里该卷是否缺题：题号断档即不完整（如 1915 缺第 19 题：18 → 20）。

        学习日志的卡片不一定带「共 N 题」，不能只靠 questionCount 判断完整性；
        题号断档是采集缺题留下的确定痕迹。
        """
        try:
            rows = ((self.matcher.paper(batch, name) or {}).get("answers") or [])
            seqs = sorted({int(r.get("seq") or 0) for r in rows if r.get("seq")})
            if not seqs:
                return False
            return seqs[-1] > len(seqs)
        except Exception:
            return False

    def _reuse_from_lib(self, task_id, batch, name, exam_id):
        """把答案库中已有的答案直接回传后端（跨账号复用），不重复抓取。"""
        try:
            detail = self.matcher.paper(batch, name)
        except Exception as exc:
            log.warning("[%s] 读取答案库 %s 失败：%s", self.name, name, exc)
            return
        items = (detail or {}).get("answers") or []
        if not items:
            return
        try:
            self.backend.post_answers(task_id, name, exam_id, items, source="reuse")
        except Exception as exc:
            log.warning("[%s] 回传复用答案 %s 失败：%s", self.name, name, exc)

    def _fill_by_fallback(self, batch, name, questions, idx):
        """本地答案库未命中的题目，调后端配置的兜底 API 补答案，就地回填 idx。"""
        missing = [i for i, j in enumerate(idx) if not _pick_count(j)]
        if not missing:
            return
        payload = [{"seq": i + 1, "stem": questions[i].get("stem", ""),
                    "options": questions[i].get("options", [])} for i in missing]
        try:
            answers = self.backend.fallback_answers(batch, name, payload)
        except Exception as exc:
            log.warning("[%s] %s 兜底答题调用失败：%s", self.name, name, exc)
            return

        filled = 0
        for item in answers or []:
            seq = item.get("seq")
            text = (item.get("answer") or "").strip()
            if not seq or not (1 <= seq <= len(idx)) or not text:
                continue
            i = seq - 1
            opts = questions[i].get("options", [])
            if not opts:
                continue
            # 兜底答案可能是「A；B」多选形式：拆分逐个定位，全部命中才算补上
            picks = []
            for part in [p.strip() for p in text.split(_ANSWER_SEP) if p.strip()]:
                try:
                    res = self.matcher.match(opts, part)
                except Exception:
                    break
                if res.get("index", -1) < 0:
                    picks = []
                    break
                picks.append(res["index"])
            if not picks:
                continue
            idx[i] = picks[0] if len(picks) == 1 else picks
            filled += 1
        log.info("[%s] %s 兜底补答 %d/%d 道", self.name, name, filled, len(missing))

    def _collect(self, driver, task_id, batch, name, exam_id, on_tick=None):
        """从结果页抓正确答案：回传后端供导出，并写回答案库（供其它账号复用）。

        无论作答模式还是采集模式，只要成功抓到答案就落库——
        「第一次作答的时候顺便记录答案」，后续别的账号即可直接复用、不再重复获取。
        """
        try:
            items = driver.collect_correct_answers(on_tick=on_tick)
        except Exception as exc:
            log.warning("[%s] 采集 %s 失败：%s", self.name, name, exc)
            return 0
        if not items:
            log.warning("[%s] %s 结果页未采到任何答案（见上一条 driver 日志）；"
                        "已答/交卷状态保留，可稍后用「只取答案」重取", self.name, name)
            return 0
        log.info("[%s] %s 采集到 %d 道正确答案，回传入库", self.name, name, len(items))
        try:
            self.backend.post_answers(task_id, name, exam_id, items)
        except Exception as exc:
            log.warning("[%s] 回传 %s 答案失败：%s", self.name, name, exc)
        try:
            res = self.collector.collect(batch, name, items)
            log.info("[%s] 落库 %s：新增 %s 更新 %s",
                     self.name, name, res.get("added"), res.get("updated"))
        except Exception as exc:
            log.warning("[%s] 写回答案库 %s 失败：%s", self.name, name, exc)
        return len(items)

    # —— 辅助 ——

    def _push_frame(self, driver, task_id, force=False):
        """回传当前页面截图（浏览器模式）。按 FRAME_INTERVAL 节流，force 时忽略节流。"""
        if not config.FRAME_ENABLED:
            return
        now = time.time()
        if not force and now - self._frame_at < config.FRAME_INTERVAL:
            return
        self._frame_at = now
        try:
            image, url = driver.snapshot_frame()
            if image:
                self.backend.post_frame(task_id, image, url)
        except Exception as exc:
            log.debug("[%s] 回传截图失败：%s", self.name, exc)

    def _push_questions(self, task_id, paper, exam_id, questions):
        """回传实时抓到的题目原文（含选项），供前端展示题目完整信息。"""
        payload = [{"seq": i + 1, "stem": q.get("stem", ""), "options": q.get("options", [])}
                   for i, q in enumerate(questions or [])]
        if not payload:
            return
        try:
            self.backend.post_questions(task_id, paper, exam_id, payload)
        except Exception as exc:
            log.warning("[%s] 回传题目失败：%s", self.name, exc)

    def _papers_of_batch(self, batch):
        manifest = self.matcher.papers()
        for b in manifest.get("batches", []):
            if b.get("batch") == batch:
                return b.get("papers", [])
        return []

    @staticmethod
    def _match_entries(papers, entries):
        """把答案库里的试卷名与页面上抓到的考试条目对上。

        一并带上条目的 answered（是否已交卷）与页面显示的状态，
        供上层区分「已交卷只读取答案」与「未交卷需先作答」。
        """
        plan = []
        for p in papers:
            name = p["name"]
            hit = next((e for e in entries if name in (e.get("name") or "")), None)
            plan.append({
                "name": name,
                "examId": (hit or {}).get("examId"),
                "courseId": (hit or {}).get("courseId"),
                "entryText": (hit or {}).get("entryText"),
                "answered": bool((hit or {}).get("answered")),
                "entryStatus": (hit or {}).get("status"),
                # 卷子总题数（学习日志「共 N 题」）：用于判断库内答案是否完整
                "questionCount": (hit or {}).get("questionCount"),
            })
        return plan

    def _safe_finish(self, task, status, score=None, summary=None):
        try:
            self.backend.finish(task["taskId"], status, score=score, summary=summary)
        except Exception as exc:
            log.warning("回传 finish 失败：%s", exc)


class Dispatcher:
    """调度器：唯一领取 backend 任务的入口，把任务派给合适的执行工人。

    派发规则（同账号铁律：同一账号同一时刻只跑一个）：
    1) task.reuseYkt 命中某个空闲工人已登录的账号 → 派给它（免扫码续做）；
    2) 否则任意空闲工人 → 新上下文扫码；
    3) 无空闲工人 → 任务停放，工人空闲后立即派发（排队）。
    """

    def __init__(self, workers):
        self.workers = workers
        self.backend = BackendClient()
        self.base = config.BACKEND_BASE
        self.parked = []
        self._lock = threading.Lock()

    def _adopt_orphans(self):
        """收养孤儿任务：上次 Agent 进程崩溃时正在执行的任务（RUNNING 但无人管）。"""
        try:
            r = requests.get(self.base + "/api/agent/tasks/running", timeout=10)
            r.raise_for_status()
            tasks = r.json() or []
        except Exception as exc:
            log.warning("收养孤儿任务失败（忽略）：%s", exc)
            return
        for task in tasks:
            tid = task.get("taskId")
            log.info("收养孤儿任务 #%s（上次进程遗留）", tid)
            self.parked.append(task)

    def run_forever(self):
        log.info("调度器启动：%d 个执行工人，轮询 backend %s", len(self.workers), config.BACKEND_BASE)
        self._adopt_orphans()
        last_warn = 0.0
        while True:
            try:
                task = self.backend.next_task()
            except Exception as exc:
                # 连不上后端时限频告警（默认每 30s 一条），并给出可操作提示：
                # 常见原因是 Agent 带着启动时的旧端口（后端换过端口 / 启动器重启过）
                now = time.time()
                if now - last_warn >= 30:
                    last_warn = now
                    log.warning(
                        "连不上后端 %s（%s）。请确认后端已启动；若后端端口变过，"
                        "在启动器里点 Agent 的「重启」即可对齐地址", config.BACKEND_BASE, exc)
                task = None
            if task:
                tid = task.get("taskId")
                log.info("调度器领到任务 #%s reuseYkt=%s", tid, task.get("reuseYkt"))
                with self._lock:
                    self.parked.append(task)
            self._dispatch()
            time.sleep(config.POLL_INTERVAL)

    def _dispatch(self):
        feeds = []
        with self._lock:
            remaining = []
            for task in self.parked:
                worker = self._pick(task)
                if worker is None:
                    remaining.append(task)
                else:
                    feeds.append((task, worker))
                    log.info("任务 #%s → 工人 %s", task.get("taskId"), worker.name)
            self.parked = remaining
            parked_ids = [t.get("taskId") for t in self.parked]
        # 放锁后派发，避免与 feed 竞争；给停放任务补一条排队提示
        for task, worker in feeds:
            worker.feed(task)
        for tid in parked_ids:
            try:
                self.backend.post_progress(tid, message="已排队，等待空闲执行位（多账号并行中）")
            except Exception:
                pass

    def _pick(self, task):
        """挑执行工人。多账号铁律：同一雨课堂账号同时只允许一个任务。

        - 任务声明了 reuseYkt（续做）：优先派给已登录该账号的空闲工人；
          若该账号正被其它任务占用 → 返回 None（任务排队等待）。
        - 无 reuseYkt 的新任务：派给任意空闲工人（其扫码决定账号归属）。
        """
        want = task.get("reuseYkt")
        busy_accounts = set()
        for w in self.workers:
            if not w.idle():
                cur = getattr(w, "current_task", None)
                if cur and cur.get("reuseYkt"):
                    busy_accounts.add(cur["reuseYkt"])
                if w.ykt_name:
                    busy_accounts.add(w.ykt_name)
        if want and want in busy_accounts:
            log.info("账号 %s 正被其它任务占用，任务 #%s 排队等待",
                     want, task.get("taskId"))
            return None
        if want:
            for w in self.workers:
                if w.idle() and w.ykt_name == want:
                    return w
        for w in self.workers:
            if w.idle():
                return w
        return None
