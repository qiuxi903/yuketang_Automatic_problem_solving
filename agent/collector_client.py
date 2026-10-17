"""collector 服务客户端：把结果页抓到的正确答案写回答案库。"""

import requests

import config


class CollectorClient:
    def __init__(self, base=None):
        self.base = (base or config.COLLECTOR_BASE).rstrip("/")

    def collect(self, batch, paper, items):
        payload = {"batch": batch, "paper": paper, "items": items}
        r = requests.post(self.base + "/collect", json=payload, timeout=30)
        r.raise_for_status()
        return r.json()
