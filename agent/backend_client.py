"""与 backend 的内部接口客户端，见 docs/接口契约.md 第六节。"""

import threading

import requests

import config

# 多工人并行时，进度/答案回传可能同时发生：串行化写请求，配合后端锁等待消除偶发 500
_write_lock = threading.Lock()


class TaskGone(Exception):
    """任务已不存在（被用户删除）：调用方应立即停止执行，不算失败。"""


class BackendClient:
    def __init__(self, base=None):
        self.base = (base or config.BACKEND_BASE).rstrip("/")

    def get_running_tasks(self):
        """后端当前 RUNNING 状态的任务清单（用于占用登记的过期自救）。"""
        r = requests.get(self.base + "/api/agent/tasks/running", timeout=10)
        r.raise_for_status()
        return r.json()

    def next_task(self):
        r = requests.get(self.base + "/api/agent/tasks/next", timeout=10)
        if r.status_code == 204:
            return None
        r.raise_for_status()
        return r.json()

    def post_qrcode(self, task_id, qrcode):
        return self._post(task_id, "qrcode", {"qrcode": qrcode})

    def post_session_status(self, task_id, status, ykt_name=None):
        return self._post(task_id, "session-status", {"status": status, "yktName": ykt_name})

    def post_papers(self, task_id, papers, course=None):
        """回传扫码登录后抓到的账号实际作业清单（含所在课程名）。"""
        return self._post(task_id, "papers", {"papers": papers, "course": course})

    def batch_decision(self, task_id):
        """轮询批次决策，返回 {status, batch}；batch 非空即可继续作答。"""
        r = requests.get(f"{self.base}/api/agent/tasks/{task_id}/batch", timeout=10)
        r.raise_for_status()
        return r.json()

    def post_progress(self, task_id, paper, exam_id=None, seq=None, total=None,
                      score=None, status=None, message=None):
        with _write_lock:
            return self._post(task_id, "progress", {
                "paper": paper, "examId": exam_id, "seq": seq, "total": total,
                "score": score, "status": status, "message": message,
            })

    def post_answers(self, task_id, paper, exam_id, items, source="collect"):
        """回传正确答案。source=collect 结果页新采集（计入社区贡献）/ reuse 复用库内答案。"""
        with _write_lock:
            return self._post(task_id, "answers", {
                "paper": paper, "examId": exam_id, "items": items, "source": source,
            })

    def post_questions(self, task_id, paper, exam_id, questions):
        """回传实时抓到的题目原文（含选项），供前端展示题目完整信息。"""
        return self._post(task_id, "questions", {
            "paper": paper, "examId": exam_id, "questions": questions,
        })

    def post_frame(self, task_id, image, url=None):
        """回传当前页面截图（dataURL），供前端浏览器模式查看雨课堂原始页面。"""
        return self._post(task_id, "frame", {"image": image, "url": url})

    def fallback_answers(self, batch, paper, questions):
        """本地答案库未命中的题目交给后端配置的兜底 API 作答。"""
        payload = {"batch": batch, "paper": paper, "questions": questions}
        r = requests.post(self.base + "/api/answer/fallback", json=payload, timeout=60)
        r.raise_for_status()
        return r.json().get("answers", [])

    def finish(self, task_id, status, score=None, summary=None):
        return self._post(task_id, "finish", {"status": status, "score": score, "summary": summary})

    def _post(self, task_id, action, payload):
        url = f"{self.base}/api/agent/tasks/{task_id}/{action}"
        r = requests.post(url, json=payload, timeout=15)
        if r.status_code == 404:
            raise TaskGone(f"任务 #{task_id} 已不存在（可能被用户删除）")
        if r.status_code >= 500:
            raise requests.HTTPError(f"{r.status_code} Server Error: {url} 原因: {r.text[:160]}")
        r.raise_for_status()
        return r.json()
