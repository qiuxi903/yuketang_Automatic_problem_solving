"""matcher 服务：为 agent 提供题干匹配与作答校验。

启动：uvicorn app:app --host 0.0.0.0 --port 8100
"""

from typing import List, Optional

from fastapi import FastAPI
from pydantic import BaseModel

import core

app = FastAPI(title="ykt-matcher", version="0.1.0")


class Option(BaseModel):
    text: str


class MatchRequest(BaseModel):
    stem: str
    options: List[str]
    answer: str
    batch: Optional[str] = None
    paper: Optional[str] = None


class MatchResponse(BaseModel):
    index: int
    score: float


class VerifyRequest(BaseModel):
    batch: str
    paper: str
    selected: List[str]


class IdxRequest(BaseModel):
    batch: str
    paper: str
    questions: List[dict]


@app.get("/health")
def health():
    return {"status": "ok", "answerLib": core.ANSWER_LIB_DIR}


@app.get("/papers")
def papers():
    return core.load_manifest()


@app.get("/papers/{batch}/{paper}")
def paper_detail(batch: str, paper: str):
    rows = core.load_paper(batch, paper)
    if rows is None:
        return {"error": "NO_ANSWER_FILE"}
    return {"batch": batch, "paper": paper, "questions": len(rows),
            "answers": [{"seq": i + 1, "stem": s, "answer": a} for i, (s, a) in enumerate(rows)]}


@app.post("/match", response_model=MatchResponse)
def match(req: MatchRequest):
    index, score = core.match_index(req.options, req.answer)
    return MatchResponse(index=index, score=score)


@app.post("/idx")
def build_idx(req: IdxRequest):
    """按答案库为整卷生成选项索引，供 agent 直接点击。"""
    answers = core.load_paper(req.batch, req.paper)
    if answers is None:
        return {"error": "NO_ANSWER_FILE"}
    idx, low = [], []
    for i, q in enumerate(req.questions):
        expected = answers[i][1] if i < len(answers) else None
        j, s = core.match_index(q.get("options", []), expected)
        idx.append(j)
        if j < 0 or s < 0.80:
            low.append({"q": i + 1, "score": round(s, 3), "expected": expected})
    return {"batch": req.batch, "paper": req.paper, "idx": idx, "lowConfidence": low}


@app.post("/verify")
def verify(req: VerifyRequest):
    return core.verify(req.batch, req.paper, req.selected)
