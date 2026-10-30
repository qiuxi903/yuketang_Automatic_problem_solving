"""雨课堂浏览器驱动：扫码登录、抓题、作答、交卷、取分。

选择器于 2026-10-03 / 2026-10-05 在真实账号（雷智宸，课程「高二理论」、试卷「硬件2010」）校准：
- 账号名：气泡 .userInfoPopover p.name（常态隐藏，但在 DOM 中），源头是 localStorage 的 vuex / Report.Daily.User
- 课程卡：「我听的课」页 .lesson-cardS，课程名在 .left .top h1，班级 .className，教师 .teacherName
- 学习日志试卷卡片：div.activity-box，名称在 h2、满分/题数在 .sub-info、得分在 .bigWord
- 考试详情页：/v2/web/exam/{courseId}/{examId}，按钮「开始答题 / 再次答题」
- 考前提示页：/start/{examId}，点「开始」进入答题页
- 答题页 / 结果页题目块：.item-body（题干 h4.exam-font、选项 label.el-radio、选项文字 .radioText）
- 结果页正确项：.dot-success 所在 label 的 .radioText
"""

import base64
import json
import re
import logging
import time

log = logging.getLogger("agent.driver")

from playwright.sync_api import sync_playwright

import config

# —— 页面选择器（2026-10-05 真机校准；2026-10-28 兼容多选题 checkbox）——
QUESTION_BLOCK = ".item-body"                  # 答题页题目块
STEM = "h4.exam-font"                          # 题干
OPTION = "label.el-radio, label.el-checkbox"   # 选项（单选 radio / 多选 checkbox）
OPTION_TEXT = ".radioText"                     # 选项文字
OPTION_CHECKED = "label.el-radio.is-checked, label.el-checkbox.is-checked"  # 已勾选
RESULT_BLOCK = ".item-body"                    # 结果页题目块
RESULT_STEM = "h4.exam-font"                   # 结果页题干
RESULT_CORRECT = ".dot-success"                # 结果页正确项标记

# 多选题多个正确项在答案库里用全角分号连接（如 "A；B"），与选项文本不会撞车
ANSWER_SEP = "；"

PAPER_CARD = "div.activity-box"                # 学习日志试卷卡片
COURSE_CARD = ".lesson-cardS"                  # 「我听的课」课程卡
COURSE_NAME_EL = ".lesson-cardS .left .top h1" # 课程卡上的课程名
NAME_POPOVER = ".userInfoPopover p.name"       # 头像气泡里的账号名（你可能好，X）
EXAM_DETAIL = "https://www.yuketang.cn/v2/web/exam/{c}/{e}"
RESULT_PAGE = "https://examination.xuetangx.com/result/{e}?isFrom=2"  # 结果页：已交卷试卷只读取答案

PAPER_ENTRY_TEXTS = ("开始答题", "再次答题", "开始作答", "再次作答", "继续答题")
RESULT_ENTRY_TEXTS = ("查看试卷", "查看答案", "查看解析")  # 详情页进入结果页的按钮


class LoginRequired(Exception):
    """需要用户手动扫码登录。"""


class AbortTask(Exception):
    """任务被用户暂停/中断：调用方应在安全点立即停止，不算失败。"""


class Driver:
    def __init__(self, headless=None, profile=None):
        self.headless = config.HEADLESS if headless is None else headless
        # 每个执行工人一个持久化浏览器 profile：登录态跨 Agent 重启保留，
        # 同一雨课堂账号续做时不用重新扫码（切账号仍会清空登录，防串号）
        self.profile = profile or "default"
        self._pw = None
        self.browser = None
        self.context = None
        self.page = None
        # 当前课程上下文：做完一卷后浏览器停在结果页，回学习日志要用
        self.course_name = None
        self.course_url = None
        # 采集/复用模式下置 True：任何调用 submit() 都会直接抛错，杜绝重复消耗作答次数
        self.submit_forbidden = False

    def _profile_dir(self):
        import os
        here = os.path.dirname(os.path.abspath(__file__))
        path = os.path.join(here, "runtime", "profiles", self.profile)
        os.makedirs(path, exist_ok=True)
        return path

    def _launch_context(self):
        """启动持久化上下文（profile 目录保留 Cookie，重启后登录态还在）。"""
        self.context = self._pw.chromium.launch_persistent_context(
            user_data_dir=self._profile_dir(),
            headless=self.headless,
            slow_mo=config.SLOW_MO,
            channel=config.BROWSER_CHANNEL or None,
            args=["--disable-blink-features=AutomationControlled"],
            user_agent=config.USER_AGENT,
            viewport={"width": 1440, "height": 900},
        )
        self.browser = None  # 持久化上下文没有独立的 browser 对象
        self.page = self.context.pages[0] if self.context.pages else self.context.new_page()
        self.page.set_default_timeout(15000)

    def start(self):
        self._pw = sync_playwright().start()
        self._launch_context()

    def reset_context(self):
        """切换账号：重建上下文并彻底清空登录态（profile 目录本身保留）。

        多账号切换的关键：雨课堂的登录态既有 Cookie 也存在 localStorage（vuex），
        必须两者都清干净——否则新任务会「继承」上一个账号的登录（串号），
        用户明明要扫码换号，却被自动登录成之前那个人。
        """
        try:
            if self.context:
                self.context.close()
        except Exception:
            pass
        self._launch_context()
        # 1) CDP 按站点清空全部存储（Cookie / localStorage / IndexedDB / cache），
        #    不依赖页面能否打开，最可靠
        try:
            cdp = self.context.new_cdp_session(self.page)
            for origin in ("https://www.yuketang.cn", "https://examination.xuetangx.com",
                           "https://www.xuetangx.com", "https://changjiang.yuketang.cn"):
                try:
                    cdp.send("Storage.clearDataForOrigin",
                             {"origin": origin, "storageTypes": "all"})
                except Exception:
                    pass
            cdp.detach()
        except Exception:
            pass
        try:
            self.context.clear_cookies()
        except Exception:
            pass
        # 2) 兜底：打开入口页再清一次本地存储（页面能打开时更保险）
        try:
            self.page.goto(config.YKT_INDEX, wait_until="domcontentloaded")
            self.page.evaluate("() => { try { localStorage.clear(); sessionStorage.clear(); } catch (e) {} }")
            self.context.clear_cookies()
        except Exception:
            pass
        self.course_name = None
        self.course_url = None

    def stop(self):
        for closable in (self.context, self.browser):
            try:
                if closable:
                    closable.close()
            except Exception:
                pass
        try:
            if self._pw:
                self._pw.stop()
        except Exception:
            pass

    def _tick_safe(self, on_tick):
        """等待循环里的回调（推帧/中止检查）。

        AbortTask 必须向上传播——用户点暂停后要立刻停手；
        其它异常（截图失败等）忽略，不能因为推帧失败打断做题。
        """
        if on_tick is None:
            return
        try:
            on_tick()
        except AbortTask:
            raise
        except Exception:
            pass

    def dismiss_popups(self):
        """关闭引导弹窗，避免遮挡点击。"""
        for text in ("我知道了", "知道了", "跳过"):
            try:
                el = self.page.get_by_text(text, exact=True).first
                if el.count() and el.is_visible():
                    el.click()
                    self.page.wait_for_timeout(600)
                    return
            except Exception:
                continue

    # —— 登录 ——

    def _login_page_shown(self):
        """当前是否停在登录页（未登录的最可靠信号：URL 带 next= 或出现二维码图）。"""
        try:
            url = self.page.url or ""
            if "next=" in url or "/login" in url.lower():
                return True
            return self.page.locator("img.logma").count() > 0
        except Exception:
            return False

    def _app_shell_shown(self):
        """当前是否渲染了应用壳（「我听的课/我教的课」导航）。"""
        try:
            return bool(self.page.locator("text=我听的课").count()
                        or self.page.locator("text=我教的课").count())
        except Exception:
            return False

    def _real_login(self):
        """真实登录：应用壳在 + 能读到账号名。

        只看应用壳不够：清空登录态后 SPA 会先闪出一层壳子（导航都在、但没有账号数据），
        据此误判「已登录」会直接跳过二维码，前端就一直显示「二维码生成中…」。
        账号名只有在真正登录后才读得到（气泡/localStorage），用它兜住竞态。
        """
        return self._app_shell_shown() and bool(self.ykt_name())

    def open_and_capture_qr(self):
        """打开入口；未登录时截取二维码，返回 dataURL；已登录返回 None。

        判定规则：
        - 出现登录页信号（URL next= / 二维码图）→ 未登录 → 截图
        - 应用壳 + 账号名同时存在且稳定 ≥1s → 已登录 → 不截图
        - 超时仍无法确认已登录 → 一律截图（宁可多显示一次二维码，也不能让用户干等）
        """
        self.page.goto(config.YKT_INDEX, wait_until="domcontentloaded")
        deadline = time.time() + 10
        logged_seen_at = 0.0
        while time.time() < deadline:
            if self._login_page_shown():
                return self.capture_qrcode()
            if self._real_login():
                if logged_seen_at == 0.0:
                    logged_seen_at = time.time()
                elif time.time() - logged_seen_at >= 1.0:
                    return None  # 真实登录且稳定 → 无需扫码
            else:
                logged_seen_at = 0.0
            self.page.wait_for_timeout(300)
        # 超时：无法确认真实登录 → 按未登录处理，截图（capture_qrcode 自带兜底截图）
        return None if self._real_login() else self.capture_qrcode()

    # 二维码必须是「接近正方形 + 尺寸足够大」的图片，
    # 用来排除 changeImg 之类的小图标（76×72、0×0 等），它们同样命中 img[src*='qr']。
    QR_MIN_SIZE = 140          # 最小边长（px）
    QR_SQUARE_TOLERANCE = 0.25  # |宽-高| / max(宽,高) 允许比例

    @staticmethod
    def _is_qr_like(box):
        """判断元素包围盒是否像二维码：够大且接近正方形。"""
        if not box:
            return False
        w, h = box.get("width") or 0, box.get("height") or 0
        if w < Driver.QR_MIN_SIZE or h < Driver.QR_MIN_SIZE:
            return False
        return abs(w - h) / max(w, h) <= Driver.QR_SQUARE_TOLERANCE

    def capture_qrcode(self):
        """截取登录二维码。

        雨课堂登录页的真实二维码是 `img.logma` 的 base64 图片（约 202×202），
        而 `img[src*='qr']` 会先命中 changeImg 小图标，所以这里改为
        「按候选选择器取，但必须通过尺寸/形状校验」，最后兜底扫描大尺寸 data 图片。
        """
        # 1) 等二维码渲染出来（微信/雨课堂二维码常异步注入）
        try:
            self.page.wait_for_selector("img.logma", timeout=8000)
        except Exception:
            pass

        candidates = ["img.logma", "img[src^='data:image']", "img[src*='qrcode']",
                      "canvas", ".qrcode", ".login-qrcode", ".qrcode-img",
                      "[class*='qrcode'] img", "img[src*='qr']"]
        for sel in candidates:
            loc = self.page.locator(sel).first
            try:
                if not (loc.count() and loc.is_visible()):
                    continue
                # 仅 data:image 开头/明确 qrcode 候选可放宽；其余一律要求像二维码
                box = loc.bounding_box()
                if sel == "img[src^='data:image']" and not self._is_qr_like(box):
                    continue
                if sel in ("img[src*='qrcode']", "canvas", ".qrcode", ".login-qrcode",
                           ".qrcode-img", "[class*='qrcode'] img", "img[src*='qr']"):
                    if not self._is_qr_like(box):
                        continue
                return "data:image/png;base64," + base64.b64encode(loc.screenshot()).decode()
            except Exception:
                continue

        # 2) 兜底：扫描页面上所有大尺寸 base64 图片，取最大的那个（通常就是二维码）
        try:
            best = self.page.evaluate(
                """
                () => {
                  let best = null;
                  for (const img of document.querySelectorAll('img')) {
                    const src = img.getAttribute('src') || '';
                    if (!src.startsWith('data:image')) continue;
                    const r = img.getBoundingClientRect();
                    if (r.width < 140 || r.height < 140) continue;
                    if (Math.abs(r.width - r.height) / Math.max(r.width, r.height) > 0.25) continue;
                    const area = r.width * r.height;
                    if (!best || area > best.area) {
                      best = { area, x: r.x, y: r.y, width: r.width, height: r.height };
                    }
                  }
                  return best;
                }
                """
            )
            if best:
                clip = {"x": best["x"], "y": best["y"], "width": best["width"], "height": best["height"]}
                return "data:image/png;base64," + base64.b64encode(
                    self.page.screenshot(clip=clip)).decode()
        except Exception:
            pass

        # 3) 末位兜底：整页截图
        return "data:image/png;base64," + base64.b64encode(self.page.screenshot()).decode()

    def is_logged_in(self):
        """是否已登录：登录页信号优先（URL 带 next= 或出现二维码），
        否则要求「应用壳 + 能读到账号名」——闪屏的应用壳不算登录。"""
        if self._login_page_shown():
            return False
        return self._real_login()

    def wait_login(self, timeout=None):
        """等待用户扫码完成。超时抛 LoginRequired。"""
        timeout = timeout or config.LOGIN_TIMEOUT
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.is_logged_in():
                return True
            self.page.wait_for_timeout(1500)
        raise LoginRequired("扫码登录超时")

    def ykt_name(self):
        """读取当前登录的雨课堂账号名，取不到返回 None。

        真实页面右侧只有一个头像，账号名藏在悬停气泡里（常态 display:none，
        但节点始终在 DOM 中），源头是 localStorage。故按 气泡 → localStorage → 旧选择器 依次尝试。
        """
        # 1) 头像气泡（即使隐藏 text_content 也能取到）
        try:
            loc = self.page.locator(NAME_POPOVER).first
            if loc.count():
                text = (loc.text_content() or "").strip()
                text = re.sub(r"^你好[，,]\s*", "", text).strip()
                if text:
                    return text
        except Exception:
            pass

        # 2) localStorage：雨课堂把用户信息写进 vuex / Report.Daily.User
        for key in ("Report.Daily.User", "vuex"):
            try:
                raw = self.page.evaluate(
                    "k => localStorage.getItem(k)", key)
                if not raw:
                    continue
                data = json.loads(raw)
                name = (data.get("name") if key != "vuex"
                        else (data.get("userInfo") or {}).get("name"))
                if name and str(name).strip():
                    return str(name).strip()
            except Exception:
                continue

        # 3) 旧选择器兜底
        for sel in (".user-name", ".nickname", ".avatar-name"):
            try:
                loc = self.page.locator(sel).first
                if loc.count() and (loc.text_content() or "").strip():
                    return (loc.text_content() or "").strip()
            except Exception:
                continue
        return None

    # —— 课程 ——

    def list_courses(self):
        """列出「我听的课」下的课程名（按页面顺序）。"""
        js = """
        () => [...document.querySelectorAll('.lesson-cardS')].map(c => {
          const h = c.querySelector('.left .top h1') || c.querySelector('h1');
          return ((h ? h.innerText : c.innerText) || '').replace(/\\s+/g, ' ').trim();
        }).filter(Boolean)
        """
        try:
            return self.page.evaluate(js)
        except Exception:
            return []

    # —— 试卷列表 ——

    def open_course(self, course_name=None):
        """进入「我听的课」下的目标课程，落在学习日志页；返回实际进入的课程名。

        course_name 为空时取列表首门课，便于未配置 AGENT_COURSE 时自动进入。
        """
        self.page.goto(config.YKT_INDEX, wait_until="domcontentloaded")
        self.page.wait_for_timeout(2500)
        self.dismiss_popups()
        try:
            self.page.get_by_text("我听的课", exact=False).first.click()
            self.page.wait_for_timeout(2000)
        except Exception:
            pass
        self.dismiss_popups()

        names = self.list_courses()
        picked = course_name
        if not picked:
            picked = names[0] if names else None
        if not picked:
            raise RuntimeError("未找到任何课程，无法进入学习日志")

        # 优先点课程卡本体的名字节点，避免命中气泡等其它同名文本
        card = self.page.locator(COURSE_CARD,
                                 has_text=re.compile(re.escape(picked))).first
        try:
            if card.count():
                card.click()
            else:
                self.page.get_by_text(picked, exact=False).first.click()
        except Exception:
            self.page.get_by_text(picked, exact=False).first.click()
        self.page.wait_for_timeout(3500)
        self.dismiss_popups()
        # 记住课程学习日志地址：多卷连做时每卷做完回这里，再点下一卷
        self.course_name = picked
        try:
            self.course_url = self.page.url
        except Exception:
            self.course_url = None
        return picked

    def back_to_paper_list(self, want_name=None, on_tick=None):
        """回到课程学习日志（试卷卡片列表）。

        做完一卷交卷后浏览器停在结果页，学习日志里根本没有下一卷的名字；
        必须先回到这里，open_paper 才能按名字点到试卷卡片。
        want_name：下一卷的名字——滚动展开时一旦看到它就停，省掉整页懒加载的等待。
        """
        if self.course_url:
            try:
                self.page.goto(self.course_url, wait_until="domcontentloaded")
                self.page.wait_for_timeout(1200)
                self.dismiss_popups()
                self._expand_log(want_name=want_name, on_tick=on_tick)
                return True
            except Exception:
                pass
        # 课程地址失效（如会话刷新）：重新走一遍入口
        self.open_course(self.course_name)
        return True

    def _expand_log(self, want_name=None, on_tick=None):
        """学习日志是「内层滚动容器」懒渲染：滚到底才会加载后续日期分组的试卷。

        页面本身不滚动（scrollHeight == clientHeight），真正的滚动容器是 .viewContainer。
        若不滚动，只会扫到首屏那一批（用户反馈「扫描不全，我全都做完了」）。
        want_name：目标试卷名——已出现即停止滚动（多卷连做时不必等整页加载完）。
        """
        try:
            self.page.wait_for_selector("div.activity-box", timeout=8000)
        except Exception:
            return
        self.page.wait_for_timeout(500)

        js_scroll = """
        () => {
          document.querySelectorAll('*').forEach(e => {
            const cs = getComputedStyle(e);
            if ((cs.overflowY === 'auto' || cs.overflowY === 'scroll') &&
                e.scrollHeight > e.clientHeight + 40) {
              e.scrollTop = e.scrollHeight;
            }
          });
          window.scrollTo(0, document.body.scrollHeight);
        }
        """
        js_count = """
        () => new Set([...document.querySelectorAll('div.activity-box .activity-info h2')]
                         .map(h => (h.innerText || '').replace(/\\s+/g, ' ').trim())
                         .filter(Boolean)).size
        """
        js_has = """
        (name) => [...document.querySelectorAll('div.activity-box .activity-info h2')]
                    .some(h => ((h.innerText || '').replace(/\\s+/g, ' ')).includes(name))
        """
        last, stable = -1, 0
        for _ in range(40):
            self._tick_safe(on_tick)
            if want_name:
                try:
                    if self.page.evaluate(js_has, want_name):
                        return  # 目标卷已出现，不必再滚
                except Exception:
                    pass
            try:
                self.page.evaluate(js_scroll)
            except Exception:
                break
            self.page.wait_for_timeout(400)
            try:
                now = self.page.evaluate(js_count)
            except Exception:
                now = last
            if now <= last:
                stable += 1
                if stable >= 2:      # 连续两轮不再增长 → 认为已加载完
                    break
            else:
                stable = 0
                last = now

    def list_papers(self, on_tick=None):
        """解析学习日志的试卷卡片与其所属日期分组。

        返回 [{name, fullScore, questionCount, score, answered, date}]。
        卡片不含 examId，需点开卡片后从地址栏取，见 open_paper。
        日期不依赖特定类名：按 DOM 顺序扫描，文本形如「09-12 周六」「12.25」
        的元素视为日期分组标题，其后的卡片都归属该日期。
        """
        self._expand_log(on_tick=on_tick)   # 先把懒加载的分组全部滚出来（期间检查暂停）
        js = r"""
        () => {
          const out = [];
          const seen = new Set();
          const dateRe = /^\s*(\d{1,2}[-.．]\d{1,2})(\s*周[一二三四五六日天])?\s*$/;
          let currentDate = '';
          const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
          let node;
          while ((node = walker.nextNode())) {
            if (node.matches('div.activity-box')) {
              const info = node.querySelector('.activity-info');
              if (!info) continue;
              const sub = ((node.querySelector('.sub-info') || {}).innerText || '').replace(/\s+/g, ' ');
              const mFull = sub.match(/满分[:：]?\s*(\d+)\s*分/);
              const mCount = sub.match(/共\s*(\d+)\s*题/);
              if (!mFull && !mCount) continue;              // 非考试类活动，跳过
              const h = info.querySelector('h2');
              const name = ((h ? h.innerText : info.innerText) || '')
                .replace(/\s+/g, ' ').trim();
              if (!name || seen.has(name)) continue;
              seen.add(name);
              const scoreEl = node.querySelector('.bigWord');
              const stat = ((node.querySelector('.statistics-box') || {}).innerText || '').replace(/\s+/g, ' ').trim();
              out.push({
                name,
                fullScore: mFull ? parseInt(mFull[1], 10) : null,
                questionCount: mCount ? parseInt(mCount[1], 10) : null,
                score: scoreEl ? (scoreEl.innerText || '').trim() : null,
                status: stat,
                answered: /得分/.test(stat),
                date: currentDate
              });
            } else if (node.childElementCount === 0) {
              const t = (node.innerText || '').trim();
              if (t && dateRe.test(t)) {
                const cand = t.replace(/\s+/g, ' ');
                if (cand !== currentDate) currentDate = cand;
              }
            }
          }
          return out;
        }
        """
        try:
            return self.page.evaluate(js)
        except Exception:
            return []

    def open_paper(self, name, on_tick=None):
        """在学习日志点开指定试卷，等待跳转，返回 (courseId, examId)。

        当前页面找不到该试卷（典型：上一卷交卷后停在结果页）时，
        自动回到学习日志再点一次——多卷连做的关键翻页逻辑。
        """
        before = self.page.url
        try:
            self.page.get_by_text(name, exact=False).first.click(timeout=6000)
        except Exception:
            self.back_to_paper_list(want_name=name, on_tick=on_tick)
            self.page.get_by_text(name, exact=False).first.click(timeout=6000)
        try:
            self.page.wait_for_url(lambda u: u != before, timeout=8000)
        except Exception:
            pass
        self.page.wait_for_timeout(900)
        m = re.search(r"/exam/(\d+)/(\d+)", self.page.url)
        return (m.group(1), m.group(2)) if m else (None, None)

    # —— 答题 ——

    def open_exam(self, course_id, exam_id, on_tick=None):
        """直接走考试详情页路由（备用入口）。自适应等待：详情页按钮一出现就返回。"""
        self.page.goto(EXAM_DETAIL.format(c=course_id, e=exam_id), wait_until="domcontentloaded")
        deadline = time.time() + 3
        while time.time() < deadline:
            self._tick_safe(on_tick)
            if self.exam_entry_state() != "unknown":
                return
            self.page.wait_for_timeout(250)

    # —— 结果页只读（已交卷试卷取答案，绝不进入答题/交卷）——

    def _on_cover_page(self):
        """当前页面是否是「考试须知」封面页（而非带答案的结果页）。"""
        try:
            if "/cover/" in (self.page.url or ""):
                return True
            if self.page.locator(RESULT_BLOCK).count():
                return False  # 已有题目块，就是结果页
            body = self.page.locator("body").inner_text() or ""
            return ("考试须知" in body or "再次作答" in body) and "答案：" not in body
        except Exception:
            return False

    def open_result(self, exam_id, on_tick=None):
        """直接打开结果页读取正确答案，不进入答题、不交卷，返回是否就绪。

        已交卷的试卷其正确项（.dot-success）只有在结果页才可见，
        直接走 /result/{examId} 即可复用，避免「再次答题」再消耗一次作答次数。
        部分卷子 /result/ 会重定向到「考试须知」封面页（/cover/），
        此时自动点「查看试卷」进入真正的结果页。
        """
        try:
            self.page.goto(RESULT_PAGE.format(e=exam_id), wait_until="domcontentloaded")
        except Exception:
            return False
        self.page.wait_for_timeout(900)
        if self._wait_result_ready(on_tick=on_tick):
            return True
        # 落在封面页（考试须知/查看试卷/再次作答）→ 点「查看试卷」进结果页
        if self._on_cover_page():
            log.info("[driver] 结果页跳转到了封面页，点「查看试卷」进入答案页")
            if self.open_result_from_detail(on_tick=on_tick):
                return True
        return False

    def open_result_from_detail(self, on_tick=None):
        """在考试详情页/封面页点「查看试卷」进入结果页（新标签页），返回是否就绪。

        作为 open_result 直连路由的兜底：部分账号/试卷仅能从详情页按钮进入结果页。
        """
        for text in RESULT_ENTRY_TEXTS:
            try:
                loc = self.page.get_by_text(text, exact=True).first
                if not loc.count():
                    continue
            except Exception:
                continue
            target = self.page
            try:
                with self.page.context.expect_page(timeout=7000) as pop:
                    loc.click(timeout=6000)
                target = pop.value
                target.wait_for_load_state("domcontentloaded")
            except Exception:
                pass
            target.wait_for_timeout(900)
            self.page = target
            return self._wait_result_ready(on_tick=on_tick)
        return False

    def exam_score_on_page(self):
        """从详情/封面页文本解析「试卷得分 X/ Y」，返回得分 X（整数）；读不到返回 None。

        空白交卷的卷子封面页会显示「试卷得分 0/ 50」，用于区分「已交卷但 0 分」与「有人做出过分数」。
        """
        try:
            self._wait_result_ready(5000)
        except Exception:
            pass
        js = r"""
        () => {
          const t = document.body.innerText || '';
          const m = t.match(/试卷得分\s*(\d+)\s*\/\s*(\d+)/);
          return m ? parseInt(m[1], 10) : null;
        }
        """
        try:
            v = self.page.evaluate(js)
            return int(v) if v is not None else None
        except Exception:
            return None

    def exam_entry_state(self):
        """考试详情页的作答状态判断（防重复保险）。

        返回：
          "fresh" — 未作答或作答中（有 开始答题/继续答题 按钮），可以进入作答；
          "done"  — 已交卷（显示 再次答题/查看试卷 等），不应重复作答；
          "unknown" — 无法识别（按未作答处理，进入后失败会如实报错）。

        注意：「继续答题」= 有未交卷的作答（中途退出过），必须按 fresh 处理——
        此前把它归为 done，会误判为「已交卷」而跳过作答、只去采集答案（必然失败）。
        """
        try:
            for text in ("再次答题", "再次作答", "查看试卷", "查看答案", "查看解析"):
                if self.page.get_by_text(text, exact=True).count():
                    return "done"
            for text in ("开始答题", "开始作答", "继续答题"):
                if self.page.get_by_text(text, exact=True).count():
                    return "fresh"
        except Exception:
            pass
        return "unknown"

    def _find_question_page(self):
        """在当前标签页及上下文里找「有题目块」的页面（开始按钮可能另开标签页）。"""
        try:
            if self.page.locator(QUESTION_BLOCK).count():
                return self.page
        except Exception:
            pass
        try:
            for p in self.page.context.pages:
                if p is self.page:
                    continue
                try:
                    if p.locator(QUESTION_BLOCK).count():
                        return p
                except Exception:
                    continue
        except Exception:
            pass
        return None

    def enter_exam(self, on_tick=None):
        """在考试详情页点「开始答题 / 再次答题」，并在提示页点「开始」，进入答题页。

        关键：考前提示页是 SPA，点「开始答题」后按钮渲染有延迟——
        必须「轮询等开始按钮出现再点」，绝不能点完立刻找（找不到就会跳过点击，
        页面永远停在提示页，最终报「答题页未就绪」）。点完同样轮询题目块。
        """
        page = self.page
        for text in PAPER_ENTRY_TEXTS:
            try:
                loc = page.get_by_text(text, exact=True).first
                if not loc.count():
                    continue
            except Exception:
                continue
            target = page
            try:
                with page.context.expect_page(timeout=6000) as pop:
                    loc.click(timeout=3000)
                target = pop.value
                target.wait_for_load_state("domcontentloaded")
            except Exception:
                pass
            self.page = target

            # 等「开始」按钮渲染出来（最多 8s，出现即点）
            clicked = False
            deadline = time.time() + 8
            while time.time() < deadline:
                self._tick_safe(on_tick)
                try:
                    start = target.get_by_text("开始", exact=True).last
                    if start.count() and start.is_visible():
                        start.click(timeout=3000)
                        clicked = True
                        break
                except Exception:
                    pass
                target.wait_for_timeout(300)

            # 等题目块出现（最多 15s）；「开始」若另开了新标签页也跟着切过去
            deadline = time.time() + 15
            while time.time() < deadline:
                self._tick_safe(on_tick)
                found = self._find_question_page()
                if found is not None:
                    self.page = found
                    log.info("[driver] 已进入答题页（入口「%s」，开始按钮%s）",
                             text, "已点击" if clicked else "未出现")
                    return True
                target.wait_for_timeout(300)
            log.warning("[driver] 点击「%s」后 15s 未出现题目块（开始按钮%s，当前 URL：%s）",
                        text, "已点击" if clicked else "未出现", (self.page.url or "")[:90])
            return clicked  # 已点开始：交给 wait_exam_ready 继续等；未点到则算失败
        return False

    def wait_exam_ready(self, timeout=30000, on_tick=None):
        """等待答题页渲染完成（题目块出现；另开标签页也能找到）。"""
        deadline = time.time() + timeout / 1000
        while time.time() < deadline:
            self._tick_safe(on_tick)
            found = self._find_question_page()
            if found is not None:
                self.page = found
                return True
            self.page.wait_for_timeout(400)
        return False

    def scrape_questions(self):
        """抓取题目：返回 [{stem, options:[文本], type}]，按页面题块顺序，与答案库题号一一对应。

        2026-10-28 起不再过滤无选项的题块：填空/简答等新题型也要占一个题号，
        否则它之后的题会与答案库序号错位（点错选项比漏答严重得多）。
        无选项题 options=[]，后续匹配会给 -1 自动跳过。
        type：single（radio）/ multi（checkbox，多选题）。
        """
        js = """
        () => {
          const blocks = [...document.querySelectorAll('.item-body')];
          return blocks.map(b => {
            const stemEl = b.querySelector('h4.exam-font') || b.querySelector('h4, .stem, .title');
            const stem = (stemEl ? stemEl.innerText : (b.innerText || '')).trim();
            const opts = [...b.querySelectorAll('label.el-radio, label.el-checkbox')].map(l => {
              const t = l.querySelector('.radioText');
              const el = t || l;
              const text = ((el.innerText || '') || '').trim();
              const html = (el.innerHTML || '');
              const normSp = (s) => (s || '').replace(/\\s+/g, ' ').trim();
              // 选项内嵌标签（链接/HTML 代码被渲染）时附「标记签名」：
              // 否则多个选项可见文字相同（如三个「百度」）时无法区分，必然选错
              if (normSp(html.replace(/<[^>]*>/g, '')) !== normSp(text)) {
                return text + ' ⟦' + normSp(html) + '⟧';
              }
              const hrefs = [...l.querySelectorAll('a[href]')]
                .map(a => a.getAttribute('href')).filter(Boolean);
              if (hrefs.length) return text + ' ⟦' + hrefs.join('|') + '⟧';
              // 提示属性里的完整内容（部分页面把被截断的 HTML 放在 title 里）
              const tips = [...l.querySelectorAll('[title], [data-tip], [data-content]')]
                .map(e => e.getAttribute('title') || e.getAttribute('data-tip') || e.getAttribute('data-content'))
                .filter(Boolean);
              if (tips.length) return text + ' ⟦' + normSp(tips.join('|')) + '⟧';
              return text;
            });
            const multi = !!b.querySelector('label.el-checkbox');
            return {stem, options: opts, type: multi ? 'multi' : 'single'};
          });
        }
        """
        return self.page.evaluate(js)

    def click_answers(self, idx_list, batch=8, on_progress=None):
        """按题目顺序点击选项；每 batch 题做一次停顿。

        idx_list 的每一项来自 matcher 的文本匹配结果，绝不按页面顺序猜字母：
        - int：单选题的选项索引（负数/None = 该题无选项或未匹配，跳过）；
        - list[int]：多选题命中的多个选项索引，逐个点击。
        on_progress(i)：每处理完第 i 题（0 基）回调一次，供 worker 实时回报题号进度。
        """
        blocks = self.page.locator(QUESTION_BLOCK)
        total = blocks.count()
        clicked = 0
        for i in range(min(total, len(idx_list))):
            pick = idx_list[i]
            picks = list(pick) if isinstance(pick, (list, tuple)) else (
                [pick] if isinstance(pick, int) and pick >= 0 else [])
            if picks:
                block = blocks.nth(i)
                block.scroll_into_view_if_needed()
                opts = block.locator(OPTION)
                for j in picks:
                    if 0 <= j < opts.count():
                        opts.nth(j).click()
                        clicked += 1
            if on_progress is not None:
                try:
                    on_progress(i)
                except AbortTask:
                    raise  # 用户暂停/中断：停止点击，交由 worker 在安全点收尾
                except Exception:
                    pass
            if (i + 1) % batch == 0:
                self.page.wait_for_timeout(300)
        return clicked

    def checked_count(self):
        return self.page.locator(OPTION_CHECKED).count()

    def _sleep_ticks(self, seconds, on_tick=None):
        """带推帧的短等待（中止信号正常传播）。"""
        deadline = time.time() + seconds
        while time.time() < deadline:
            self._tick_safe(on_tick)
            left = deadline - time.time()
            self.page.wait_for_timeout(min(300, max(50, int(left * 1000))))

    def submit(self, on_tick=None):
        """交卷：header 交卷按钮 → 确认弹窗再次点击交卷。

        这是唯一会消耗作答次数的动作。采集/复用模式下 submit_forbidden 为 True，
        此处直接抛错，从机制上保证「只获取答案，不交卷」。

        注意：点完顶部「交卷」后必须等确认弹窗真正渲染（经验值 1.2s）再点它，
        否则会把顶部按钮当成确认按钮点掉，弹窗根本没确认 → 页面停在 /exam/，
        看起来「已作答」实际未交卷（这就是任务 122 全部失败的原因）。
        """
        if self.submit_forbidden:
            raise RuntimeError("采集模式禁止交卷：已交卷试卷只读结果页，未交卷试卷需用户自行完成")
        self.page.get_by_text("交卷", exact=True).first.click()
        self._sleep_ticks(1.2, on_tick)   # 等确认弹窗渲染（推帧不冻结预览）
        confirmed = False
        try:
            # 弹窗按钮是 DOM 里最后一个「交卷」按钮；Playwright 会自动等按钮可点击
            self.page.get_by_role("button", name="交卷").last.click(timeout=5000)
            confirmed = True
        except Exception:
            try:
                self.page.get_by_text("交卷", exact=True).last.click(timeout=3000)
                confirmed = True
            except Exception:
                pass
        if not confirmed:
            log.warning("[driver] 交卷确认按钮未点中，页面可能未交卷")
        # 等交卷生效：雨课堂可能跳 /result/（直接结果页），也可能跳 /cover/
        # （考试封面页，同样代表已交卷）——只认 /result/ 会把成功误判为失败
        deadline = time.time() + 15
        while time.time() < deadline:
            self._tick_safe(on_tick)
            url = self.page.url or ""
            if "/result/" in url or "/cover/" in url or self._on_cover_page():
                self._sleep_ticks(0.4, on_tick)  # 页面首屏渲染缓冲
                log.info("[driver] 已交卷（页面跳转：%s）", url[:70])
                return
            self.page.wait_for_timeout(300)
        log.warning("[driver] 交卷后 10s 未跳转结果页（当前 URL：%s）", (self.page.url or "")[:90])
        # 未跳转 = 交卷没成功：如实报错，别把空卷当成已交卷
        raise RuntimeError("交卷未成功（确认按钮可能未点中，页面仍停留在答题页）")

    def read_score(self, on_tick=None):
        """读取得分，返回 'X/N' 或 None。交卷后先等结果页出现。"""
        try:
            self.page.wait_for_url(re.compile(r"/result/"), timeout=6000)
        except Exception:
            pass
        self._wait_result_ready(on_tick=on_tick)
        js = """
        () => {
          const t = document.body.innerText || '';
          let m = t.match(/(\\d+)\\s*\\/\\s*(\\d+)\\s*题/);
          if (!m) m = t.match(/(\\d+)\\s*\\/\\s*(\\d+)/);
          return m ? (m[1] + '/' + m[2]) : null;
        }
        """
        try:
            return self.page.evaluate(js)
        except Exception:
            return None

    def _wait_result_ready(self, timeout=15000, on_tick=None):
        """等结果页题目块渲染完成（出现即返回；等待期间回调推帧，预览不卡）。"""
        deadline = time.time() + timeout / 1000
        while time.time() < deadline:
            self._tick_safe(on_tick)
            if self.page.locator(RESULT_BLOCK).count():
                self.page.wait_for_timeout(400)
                return True
            self.page.wait_for_timeout(400)
        return False

    RESULT_SCRAPE_JS = r"""
    () => {
      // 多选题一题有多个 .dot-success：同一题块内的正确项用「；」连接成一个答案
      const SEP = '；';
      // 策略 A：标准题块 .item-body
      let blocks = [...document.querySelectorAll('.item-body')];
      let mode = 'A(.item-body)';
      if (!blocks.length) {
        // 策略 B：其它常见题块容器
        blocks = [...document.querySelectorAll(
          '[class*="question"], [class*="exam-li"], [class*="topic"], li[class*="item"]')];
        blocks = blocks.filter(b => b.querySelector('[class*="dot-success"], [class*="radio"]'));
        mode = 'B(容器兜底)';
      }
      const rows = [];
      const push = (stem, answer) => rows.push({seq: rows.length + 1,
        stem: (stem || '').replace(/\s+/g, ' ').trim(),
        answer: (answer || '').replace(/\s+/g, ' ').trim()});
      const normSp = (s) => (s || '').replace(/\s+/g, ' ').trim();
      // 选项文字提取：与答题页抓题保持同一规则——选项内嵌标签（链接/HTML 代码）
      // 时附加「标记签名」，否则多个选项可见文字相同（如三个「百度」）无法区分
      const optText = (labelEl) => {
        if (!labelEl) return '';
        const t = labelEl.querySelector('.radioText');
        const el = t || labelEl;
        const text = ((el.innerText || '') || '').trim();
        const html = (el.innerHTML || '');
        if (normSp(html.replace(/<[^>]*>/g, '')) !== normSp(text)) {
          return text + ' ⟦' + normSp(html) + '⟧';
        }
        const hrefs = [...labelEl.querySelectorAll('a[href]')]
          .map(a => a.getAttribute('href')).filter(Boolean);
        if (hrefs.length) return text + ' ⟦' + hrefs.join('|') + '⟧';
        const tips = [...labelEl.querySelectorAll('[title], [data-tip], [data-content]')]
          .map(e => e.getAttribute('title') || e.getAttribute('data-tip') || e.getAttribute('data-content'))
          .filter(Boolean);
        if (tips.length) return text + ' ⟦' + normSp(tips.join('|')) + '⟧';
        return text;
      };
      const collectMarks = (b) => {
        const oks = [...b.querySelectorAll('.dot-success, [class*="dot-success"]')];
        const labels = [...b.querySelectorAll('label.el-radio, label.el-checkbox')];
        const parts = [];
        for (const ok of oks) {
          const row = ok.closest('label') || ok.parentElement;
          const txt = optText(row);
          if (txt) parts.push(txt);
        }
        // 记录正确选项的位置（1 基，多选用逗号）：选项可见文字相同时
        // （如三个「百度」），答案文本无法区分，位置是唯一的消歧线索
        const positions = oks.map(ok => labels.indexOf(ok.closest('label')))
          .filter(i => i >= 0).map(i => i + 1);

        // 兜底：个别题目没有正确标记（如 1915 第 19 题），但页面文字里
        // 会显示「正确答案：C」——按字母对应到选项位置取原文
        if (!parts.length && labels.length) {
          const m = (b.innerText || '').match(/正确答案\s*[:：]\s*([A-Da-d]+)/);
          if (m) {
            const idxs = [...m[1].toUpperCase()]
              .map(ch => ch.charCodeAt(0) - 65)
              .filter(i => i >= 0 && i < labels.length);
            for (const i of idxs) {
              const txt = optText(labels[i]);
              if (txt) parts.push(txt);
            }
            if (parts.length) {
              return parts.join(SEP) + ' ⟦pos:' + idxs.map(i => i + 1).join(',') + '⟧';
            }
          }
        }

        let answer = parts.join(SEP);
        if (answer && positions.length) {
          answer += ' ⟦pos:' + positions.join(',') + '⟧';
        }
        return answer;
      };
      if (blocks.length) {
        for (const b of blocks) {
          const stemEl = b.querySelector('h4.exam-font, [class*="stem"], h3, h4') || b;
          const stem = stemEl.innerText || '';
          push(stem, collectMarks(b));
        }
        return {mode, count: rows.length, rows};
      }
      // 策略 C：整页找正确标记，按爬到的题块分组（同一题块的多个正确项合成一条）
      const marks = [...document.querySelectorAll('.dot-success, [class*="dot-success"]')];
      const byBlock = new Map();
      for (const m of marks) {
        let box = m;
        for (let up = 0; up < 8 && box; up++) {
          box = box.parentElement;
          if (box && /题|question|item|exam/i.test(box.className || '') && box.innerText.length > 20) break;
        }
        const row = m.closest('label') || m.parentElement;
        const answer = optText(row);
        if (!answer) continue;
        const key = box || document.body;
        if (!byBlock.has(key)) {
          const stem = (key && key !== document.body)
            ? (key.innerText || '').split('\n').slice(0, 3).join(' ') : '';
          byBlock.set(key, {stem, parts: []});
        }
        byBlock.get(key).parts.push(answer);
      }
      for (const {stem, parts} of byBlock.values()) push(stem, parts.join(SEP));
      return {mode: 'C(全局正确标记)', count: rows.length, rows};
    }
    """

    def _frames(self):
        """主文档 + 全部子框架（结果页内容可能渲染在 iframe 里）。"""
        seen, out = set(), []
        for f in [self.page.main_frame, *self.page.frames]:
            if id(f) not in seen:
                seen.add(id(f))
                out.append(f)
        return out

    def _wait_answer_visible(self, timeout=25000, on_tick=None):
        """等任意 frame 里出现正确答案标记（.dot-success）；等待期间回调推帧。"""
        deadline = time.time() + timeout / 1000
        while time.time() < deadline:
            self._tick_safe(on_tick)
            for f in self._frames():
                try:
                    if f.locator(".dot-success").count():
                        return True
                except Exception:
                    pass
            self.page.wait_for_timeout(400)
        return False

    def _scrape_frames(self):
        """在主文档与每个子框架里执行抓取脚本，返回首个命中的结果。"""
        js = self.RESULT_SCRAPE_JS
        empty = None
        for f in self._frames():
            try:
                info = f.evaluate(js)
            except Exception:
                continue
            if info and info.get("count", 0) > 0:
                info["frame"] = (f.url or "")[:80]
                return info
            empty = empty or info
        return empty

    def _dump_result_diagnostics(self):
        """采集失败时输出各 frame 的关键信息，便于远程定位页面结构。"""
        try:
            for i, f in enumerate(self._frames()):
                info = f.evaluate(
                    r"""() => ({
                      url: location.href.slice(0, 90),
                      title: document.title.slice(0, 40),
                      itemBody: document.querySelectorAll('.item-body').length,
                      dotSuccess: document.querySelectorAll('.dot-success').length,
                      iframes: document.querySelectorAll('iframe').length,
                      bodyHead: (document.body ? document.body.innerText : '').replace(/\s+/g, ' ').slice(0, 160),
                    })"""
                )
                log.warning("[driver] frame#%d %s", i, json.dumps(info, ensure_ascii=False))
        except Exception as exc:
            log.warning("[driver] 诊断信息输出失败：%s", exc)

    def collect_correct_answers(self, on_tick=None):
        """从结果页抓取每题正确选项文本（跨 iframe、多策略），返回 [{seq, stem, answer}]。"""
        if not self._wait_answer_visible(25000, on_tick=on_tick):
            log.warning("[driver] 等待 25s 仍未出现正确答案标记，执行诊断并多策略抓取")
        info = self._scrape_frames()
        if not info or not info.get("count"):
            self._dump_result_diagnostics()
            log.warning("[driver] 多策略抓取仍无答案（诊断见上）")
            return []
        all_rows = info.get("rows", [])
        rows = [r for r in all_rows if (r.get("answer") or "").strip()]
        # 明确列出「有题块但没有答案」的题号：个别题目结果页不显示正确答案，
        # 这些题无法入库（平台作答时会跳过，不瞎猜）
        missing = sorted({r.get("seq") for r in all_rows} - {r.get("seq") for r in rows})
        if missing:
            log.warning("[driver] 结果页有 %d 题未显示正确答案（题号：%s），本轮无法入库",
                        len(missing), missing[:15])
        log.info("[driver] 结果页采集：策略 %s，%d 题带答案（frame: %s）",
                 info.get("mode"), len(rows), info.get("frame", "主文档"))
        return rows

    def back_to_index(self):
        try:
            self.page.goto(config.YKT_INDEX, wait_until="domcontentloaded")
        except Exception:
            pass

    # —— 浏览器模式 ——

    def snapshot_frame(self):
        """截取当前视口画面，返回 (dataURL, 当前URL)；失败返回 (None, None)。

        Playwright 同步 API 只能在创建它的线程调用，故截图由主线程在各关键节点触发。
        """
        try:
            raw = self.page.screenshot(type="jpeg", quality=60)
            data = "data:image/jpeg;base64," + base64.b64encode(raw).decode()
            return data, self.page.url
        except Exception:
            return None, None
