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
            # 题号用库内真实序号（可能缺题），不要用数组下标重新编号
            "answers": [{"seq": seq, "stem": s, "answer": a} for seq, s, a in rows]}


@app.post("/match", response_model=MatchResponse)
def match(req: MatchRequest):
    index, score = core.match_index(req.options, req.answer)
    return MatchResponse(index=index, score=score)


@app.post("/idx")
def build_idx(req: IdxRequest):
    """按答案库为整卷生成选项索引，供 agent 直接点击。

    idx 每一项：int（单选，负数=未匹配/无选项）或 list[int]（多选，全部命中才给列表）。
    noOptions 单列无选项题（填空/简答等新题型），agent 会如实播报并跳过。
    新卷子（本卷无答案文件）或本卷未命中的题：再尝试「跨卷同题匹配」——
    库内其它试卷出现过同一道题就直接借用其答案（很多课程题目跨卷复用）。
    """
    answers = core.load_paper(req.batch, req.paper)  # None = 新卷子
    # 按题号对齐（库内可能缺题：结果页某题没采到答案，不能连累后面所有题错位）
    per_paper = {seq: ans for seq, _stem, ans in (answers or [])}
    idx, low, noopts = [], [], []
    for i, q in enumerate(req.questions):
        expected = per_paper.get(i + 1)
        opts = q.get("options") or []
        if not opts:
            idx.append(-1)
            noopts.append({"q": i + 1, "stem": (q.get("stem") or "")[:60]})
            continue
        hits, s = core.match_multi(opts, expected or "")
        if len(hits) == 1:
            idx.append(hits[0])
        elif len(hits) > 1 and all(h >= 0 for h in hits):
            idx.append(hits)
        else:
            idx.append(-1)
        if not hits or (isinstance(idx[-1], int) and idx[-1] < 0) or s < 0.80:
            low.append({"q": i + 1, "score": round(s, 3), "expected": expected})

    # 跨卷补全：本卷没答案或没命中的题，去库内其它试卷找同题
    cross = []
    for i, q in enumerate(req.questions):
        opts = q.get("options") or []
        if not opts:
            continue
        cur = idx[i]
        if isinstance(cur, int) and cur >= 0:
            continue  # 本卷已命中
        ans, score, src = core.find_cross_answer(q.get("stem", ""), exclude_paper=req.paper)
        if not ans:
            continue
        j, ms = core.match_index(opts, ans)
        if j >= 0 and ms >= 0.80:
            idx[i] = j
            cross.append({"q": i + 1, "paper": src, "score": round(score, 3)})
    if cross:
        done = {c["q"] for c in cross}
        low = [x for x in low if x["q"] not in done]

    return {"batch": req.batch, "paper": req.paper, "idx": idx,
            "lowConfidence": low, "noOptions": noopts,
            "paperInLib": answers is not None, "crossMatched": cross}


@app.post("/verify")
def verify(req: VerifyRequest):
    return core.verify(req.batch, req.paper, req.selected)
