"""collector 服务：接收结果页采集到的正确答案，写回答案库。

启动：uvicorn app:app --host 0.0.0.0 --port 8200
"""

from typing import List

from fastapi import FastAPI
from pydantic import BaseModel

import store

app = FastAPI(title="ykt-collector", version="0.1.0")


class Item(BaseModel):
    seq: int
    stem: str = ""
    answer: str


class CollectRequest(BaseModel):
    batch: str
    paper: str
    items: List[Item]


@app.get("/health")
def health():
    return {"status": "ok", "answerLib": store.ANSWER_LIB_DIR}


@app.post("/collect")
def collect(req: CollectRequest):
    res = store.merge_paper(req.batch, req.paper,
                            [i.model_dump() for i in req.items])
    return {"ok": True, **res}
