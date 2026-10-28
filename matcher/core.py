"""题干匹配与作答校验核心逻辑。

逻辑移植自项目早期的 match_idx.py 与 verify2.py，已在 10-01、09-24 两批
共 36 套 2044 题上验证满分，此处保持算法一致，仅改为可被服务调用。
"""

import difflib
import json
import os
import re

_HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_LIB = os.path.normpath(os.path.join(_HERE, "..", "answer-lib"))
ANSWER_LIB_DIR = os.environ.get("ANSWER_LIB_DIR", DEFAULT_LIB)


def _clean(s: str) -> str:
    s = s.strip()
    s = (
        s.replace("\u201c", '"').replace("\u201d", '"')
        .replace("\u2018", "'").replace("\u2019", "'")
    )
    s = (
        s.replace("（", "(").replace("）", ")")
        .replace("，", ",").replace("。", ".")
        .replace("：", ":").replace("；", ";")
    )
    s = s.replace("\u00b7", "").replace("\u2027", "").replace("\u30fb", "")
    s = re.sub(r'[\s\.,;:()"\'\u3001\uff0c\u3002\uff01\uff1f!?]', "", s)
    return s


def norm_cs(s: str) -> str:
    """大小写敏感归一化，用于区分 MB/Mb 这类选项。"""
    return _clean(s)


def norm(s: str) -> str:
    return norm_cs(s).replace("\u7684", "").lower()


def sim(a: str, b: str) -> float:
    return difflib.SequenceMatcher(None, a, b).ratio()


def sign_of(s: str) -> str:
    s = s.strip()
    if s[:1] in ("-", "+", "\u2212"):
        return "-" if s[0] in ("-", "\u2212") else "+"
    return ""


def cands(text: str):
    """候选文本：原文优先，其次在字母选项前缀存在时剥离前缀。"""
    t = text.strip()
    out = [t]
    body = re.sub(r"^[A-Da-d][\s\.\u3001]+", "", t)
    if body and body != t:
        out.append(body)
    return out


def match_index(options, answer: str):
    """在选项文本中定位答案，返回 (索引, 得分)。找不到返回 (-1, 0.0)。"""
    if answer is None:
        return -1, 0.0
    answer = str(answer)
    ne = norm(answer)
    necs = norm_cs(answer)
    se = sign_of(answer)

    for j, text in enumerate(options):
        for body in cands(text):
            if norm_cs(body) == necs:
                return j, 1.0

    def best(use_sign: bool):
        bj, bs = -1, 0.0
        for j, text in enumerate(options):
            for body in cands(text):
                nb = norm(body)
                if use_sign and sign_of(body) != se:
                    continue
                if nb == ne:
                    return j, 1.0
                score = sim(nb, ne)
                if ne and ne in nb:
                    score = max(score, 0.97)
                if nb and nb in ne and len(nb) > 3:
                    score = max(score, 0.95)
                if score > bs:
                    bs, bj = score, j
        return bj, bs

    j, s = best(True)
    if s < 0.80:
        j2, s2 = best(False)
        if s2 >= 0.80:
            return j2, s2
    if s >= 0.80:
        return j, s
    return -1, s


def split_multi(answer: str):
    """把多选答案拆成单选项文本列表："A；B" → ["A", "B"]。单选答案原样单元素返回。"""
    if not answer:
        return []
    return [p for p in (s.strip() for s in re.split(r"\s*[;；]\s*", str(answer))) if p]


def match_multi(options, answer: str):
    """单选/多选统一入口：答案按「；」拆分逐个定位选项。

    返回 (indices, score)：
    - 单选（或只有一部分命中）→ indices 是单元素列表，语义同 match_index；
    - 多选 → indices 是命中的多个索引；任一部分未命中 → 该部分为 -1（整题视为未匹配）；
    - answer 为空 / 无选项 → ([], 0.0)，调用方按未匹配处理。
    """
    if not options or not answer:
        return [], 0.0
    parts = split_multi(answer)
    if not parts:
        return [], 0.0
    idxs, scores = [], []
    for part in parts:
        j, s = match_index(options, part)
        idxs.append(j)
        scores.append(s)
    return idxs, min(scores)


def _paper_path(batch: str, name: str) -> str:
    return os.path.join(ANSWER_LIB_DIR, batch, name + ".md")


def load_paper(batch: str, name: str):
    """读取试卷答案，返回 [(题干, 答案文本), ...]，按题号排序。"""
    path = _paper_path(batch, name)
    if not os.path.exists(path):
        return None
    txt = open(path, encoding="utf-8").read()
    blocks = re.split(r"\n(?=\*\*\d+\.\*\*)", txt)
    rows = []
    for b in blocks:
        m = re.search(r"\*\*(\d+)\.\*\*\s*(.*)", b)
        a = re.search(r"- 答案：(.+)", b)
        if m and a:
            rows.append((int(m.group(1)), m.group(2).strip(), a.group(1).strip()))
    rows.sort()
    return [(stem, ans) for _, stem, ans in rows]


def load_manifest():
    path = os.path.join(ANSWER_LIB_DIR, "manifest.json")
    if not os.path.exists(path):
        return {"batches": [], "summary": {"papers": 0, "questions": 0}}
    return json.load(open(path, encoding="utf-8"))


# —— 跨卷同题匹配：很多课程题目跨卷复用，新卷子可先用旧卷的同题答案作答 ——

_ALL_CACHE = {"mtime": None, "rows": []}


def _lib_mtime():
    """答案库整体修改时间（manifest + 各批次目录与 md 文件），用于缓存失效。"""
    m = 0.0
    try:
        m = os.path.getmtime(os.path.join(ANSWER_LIB_DIR, "manifest.json"))
    except Exception:
        pass
    try:
        for d in os.listdir(ANSWER_LIB_DIR):
            p = os.path.join(ANSWER_LIB_DIR, d)
            if not os.path.isdir(p):
                continue
            try:
                m = max(m, os.path.getmtime(p))
            except Exception:
                pass
            for f in os.listdir(p):
                if f.endswith(".md"):
                    try:
                        m = max(m, os.path.getmtime(os.path.join(p, f)))
                    except Exception:
                        pass
    except Exception:
        pass
    return m


def load_all_answers():
    """全库题目索引：[(归一化题干, 原题干, 答案, 试卷名)]，按库修改时间缓存。"""
    mt = _lib_mtime()
    if _ALL_CACHE["mtime"] == mt and _ALL_CACHE["rows"]:
        return _ALL_CACHE["rows"]
    rows = []
    for b in load_manifest().get("batches", []):
        batch = b.get("batch")
        for p in b.get("papers", []):
            name = p.get("name")
            if not name:
                continue
            for stem, ans in (load_paper(batch, name) or []):
                if stem and ans:
                    rows.append((norm(stem), stem, ans, name))
    _ALL_CACHE["mtime"] = mt
    _ALL_CACHE["rows"] = rows
    return rows


def find_cross_answer(stem, exclude_paper=None, min_score=0.92):
    """在库内其它试卷里找同一道题，返回 (答案, 置信度, 来源试卷)。

    先按归一化题干精确命中（同题复用最可靠），再对长度接近的候选做相似度比较；
    低于 min_score 不采用，避免把「相似但不是同一题」的答案张冠李戴。
    """
    target = norm(stem or "")
    if len(target) < 6:  # 太短的题干（如「判断题」）容易误配，不参与跨卷匹配
        return None, 0.0, None
    rows = load_all_answers()
    for ns, _stem, ans, paper in rows:
        if paper == exclude_paper:
            continue
        if ns == target:
            return ans, 1.0, paper
    best_ans, best_score, best_paper = None, 0.0, None
    tl = len(target)
    for ns, _stem, ans, paper in rows:
        if paper == exclude_paper:
            continue
        if abs(len(ns) - tl) > max(6, int(tl * 0.35)):
            continue
        s = sim(ns, target)
        if s > best_score:
            best_ans, best_score, best_paper = ans, s, paper
    if best_score >= min_score:
        return best_ans, best_score, best_paper
    return None, best_score, None


def verify(batch: str, name: str, selected):
    """比对作答与答案库，返回差异明细。"""
    answers = load_paper(batch, name)
    if answers is None:
        return {"error": "NO_ANSWER_FILE", "total": len(selected), "bad": len(selected)}
    bad = []
    for i, sel in enumerate(selected):
        if i >= len(answers):
            bad.append({"q": i + 1, "selected": sel, "expected": None})
            continue
        expected = answers[i][1]
        if norm_cs(sel) == norm_cs(expected):
            continue
        ns, ne = norm(sel), norm(expected)
        if ns != ne and sim(ns, ne) < 0.80 and ns not in ne and ne not in ns:
            bad.append({"q": i + 1, "selected": sel, "expected": expected})
    return {"total": len(selected), "bad": len(bad), "mismatches": bad}
