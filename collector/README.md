# collector 答案采集服务

技术栈：Python 3 + FastAPI

职责

- 接收 agent 从结果页抓取的每题正确选项文本
- 按标准格式写入 `answer-lib\<batch>\<paper>.md`（题干 + 答案文本）
- 同步更新 `answer-lib\manifest.json`（题量、试卷数、汇总）
- 采集一次长期复用，下次遇到该卷（含重复卷）直接满分

## 文件

- `store.py`：答案库读写与 manifest 同步
- `app.py`：FastAPI 入口，提供 `/health` 与 `/collect`

## 启动

```powershell
cd g:\project\做题\collector
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8200
```

## 接口

- `GET /health`
- `POST /collect`，入参 `{batch, paper, items:[{seq, stem, answer}]}`，返回 `{ok, file, added, updated, questions}`

答案库路径可用环境变量 `ANSWER_LIB_DIR` 覆盖，默认 `..\answer-lib`。

状态：P0 骨架已完成（写入与 manifest 同步已本地验证）
