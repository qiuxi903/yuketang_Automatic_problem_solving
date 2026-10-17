"""答案库写入：把采集到的正确答案按标准格式落盘，并同步 manifest.json。"""

import datetime
import json
import os
import re

_HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_LIB = os.path.normpath(os.path.join(_HERE, "..", "answer-lib"))
ANSWER_LIB_DIR = os.environ.get("ANSWER_LIB_DIR", DEFAULT_LIB)

_BLOCK = re.compile(r"\*\*(\d+)\.\*\*\s*(.*)")
_ANS = re.compile(r"- 答案：(.+)")


def _paper_path(batch, paper):
    return os.path.join(ANSWER_LIB_DIR, batch, paper + ".md")


def read_paper(batch, paper):
    """读取已有试卷，返回 {seq: (stem, answer)}。"""
    path = _paper_path(batch, paper)
    if not os.path.exists(path):
        return {}
    txt = open(path, encoding="utf-8").read()
    out = {}
    for block in re.split(r"\n(?=\*\*\d+\.\*\*)", txt):
        m = _BLOCK.search(block)
        a = _ANS.search(block)
        if m and a:
            out[int(m.group(1))] = (m.group(2).strip(), a.group(1).strip())
    return out


def render_paper(rows):
    """按标准格式渲染试卷正文。rows 为 {seq: (stem, answer)}。"""
    parts = []
    for seq in sorted(rows):
        stem, answer = rows[seq]
        parts.append("**%d.** %s\n\n- 答案：%s" % (seq, stem, answer))
    return "\n\n".join(parts) + "\n"


def _load_manifest_safe():
    path = os.path.join(ANSWER_LIB_DIR, "manifest.json")
    if not os.path.exists(path):
        return {"batches": []}
    return json.load(open(path, encoding="utf-8"))


def find_paper_batch(paper):
    """在全部批次里查找试卷，返回其所在批次名；未入库返回 None。"""
    for b in _load_manifest_safe().get("batches", []):
        for p in b.get("papers", []):
            if p.get("name") == paper:
                return b.get("batch")
    return None


def today_batch():
    """今天日期对应的批次名（如 10-15）与日期（2026-10-15）。新采的答案进这里。"""
    now = datetime.datetime.now()
    return now.strftime("%m-%d"), now.strftime("%Y-%m-%d")


def merge_paper(batch, paper, items):
    """把采集结果并入试卷文件，返回 {file, added, updated, questions, batch}。

    目标批次智能路由：试卷已在库里 → 原批次原地更新；
    新卷子 → 写入「今天」日期的批次（自动创建），答案中心按日期分组即见今日新库。
    """
    target = find_paper_batch(paper) or today_batch()[0]
    rows = read_paper(target, paper)
    added = updated = 0
    for it in items:
        seq = int(it["seq"])
        stem = (it.get("stem") or "").strip()
        answer = (it.get("answer") or "").strip()
        if not answer:
            continue
        if seq in rows:
            if rows[seq][1] != answer:
                rows[seq] = (stem or rows[seq][0], answer)
                updated += 1
        else:
            rows[seq] = (stem, answer)
            added += 1

    os.makedirs(os.path.dirname(_paper_path(target, paper)), exist_ok=True)
    with open(_paper_path(target, paper), "w", encoding="utf-8") as f:
        f.write(render_paper(rows))
    date = today_batch()[1] if target == today_batch()[0] else None
    update_manifest(target, paper, len(rows), date=date)
    return {"file": os.path.relpath(_paper_path(target, paper), ANSWER_LIB_DIR),
            "added": added, "updated": updated, "questions": len(rows), "batch": target}


def update_manifest(batch, paper, questions, date=None):
    path = os.path.join(ANSWER_LIB_DIR, "manifest.json")
    manifest = {"generatedAt": "", "format": "question-stem + answer-text",
                "batches": [], "summary": {"papers": 0, "questions": 0}}
    if os.path.exists(path):
        manifest = json.load(open(path, encoding="utf-8"))

    b = next((x for x in manifest["batches"] if x.get("batch") == batch), None)
    if b is None:
        b = {"batch": batch, "date": date or "", "papersCount": 0, "questions": 0, "papers": []}
        manifest["batches"].append(b)
    if date and not b.get("date"):
        b["date"] = date
    entry = next((p for p in b["papers"] if p.get("name") == paper), None)
    if entry is None:
        entry = {"name": paper, "file": "%s/%s.md" % (batch, paper), "questions": questions, "score": ""}
        b["papers"].append(entry)
    entry["questions"] = questions
    entry["file"] = "%s/%s.md" % (batch, paper)

    b["papersCount"] = len(b["papers"])
    b["questions"] = sum(p.get("questions", 0) for p in b["papers"])
    manifest["summary"] = {
        "papers": sum(len(x["papers"]) for x in manifest["batches"]),
        "questions": sum(x.get("questions", 0) for x in manifest["batches"]),
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
