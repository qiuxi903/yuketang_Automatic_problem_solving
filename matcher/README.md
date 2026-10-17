# matcher 匹配与校验服务

技术栈：Python 3 + FastAPI

职责

- 题目匹配：题干与选项文本归一化、相似度计算、选项前缀剥离、正负号匹配，输出选项索引 IDX
- 作答校验：作答结果与答案库比对，输出是否一致
- 复用现有 `match_idx.py` 与 `verify2.py` 的核心逻辑

接口

- `GET /health` 健康检查
- `GET /papers` 返回答案库 manifest
- `GET /papers/{batch}/{paper}` 返回单套试卷的题干与答案
- `POST /match` 输入题干与选项，返回选项索引与得分
- `POST /idx` 输入整卷题目，返回选项索引数组与低置信题
- `POST /verify` 输入作答与答案库，返回差异

## 启动

```powershell
cd g:\project\做题\matcher
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8100
```

答案库路径可用环境变量 `ANSWER_LIB_DIR` 覆盖，默认 `..\answer-lib`。

状态：P0 已完成（核心匹配与校验逻辑已在 36 套 2044 题上验证）
