"""matcher 服务客户端：题干匹配、整卷 IDX 生成、作答校验。"""

import requests

import config


class MatcherClient:
    def __init__(self, base=None):
        self.base = (base or config.MATCHER_BASE).rstrip("/")

    def health(self):
        return requests.get(self.base + "/health", timeout=5).json()

    def papers(self):
        return requests.get(self.base + "/papers", timeout=10).json()

    def paper(self, batch, paper):
        r = requests.get(f"{self.base}/papers/{batch}/{paper}", timeout=15)
        r.raise_for_status()
        return r.json()

    def match(self, options, answer, stem=None, batch=None, paper=None):
        payload = {"stem": stem or "", "options": options, "answer": answer,
                   "batch": batch, "paper": paper}
        return requests.post(self.base + "/match", json=payload, timeout=15).json()

    def build_idx(self, batch, paper, questions):
        payload = {"batch": batch, "paper": paper, "questions": questions}
        r = requests.post(self.base + "/idx", json=payload, timeout=60)
        r.raise_for_status()
        return r.json()

    def verify(self, batch, paper, selected):
        payload = {"batch": batch, "paper": paper, "selected": selected}
        r = requests.post(self.base + "/verify", json=payload, timeout=60)
        r.raise_for_status()
        return r.json()
