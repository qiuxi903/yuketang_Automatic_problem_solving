# agent Agent 执行器

技术栈：Python 3 + Playwright

职责

- 拉起浏览器完成雨课堂扫码登录，回传二维码与登录状态
- 按 examId 打开考试、抓取题目 DOM、按 matcher 输出的索引点击选项
- 提交前调用 matcher 校验，再交卷
- 抓取得分并回写 backend；采集模式下把结果页正确答案交给 collector
- 轮询 backend 领取任务，支持多实例并发

## 文件

- `config.py`：运行配置，全部可用环境变量覆盖
- `backend_client.py`：backend 内部接口（领取任务、回传二维码/状态/进度）
- `matcher_client.py`：匹配与校验服务客户端
- `collector_client.py`：答案采集服务客户端
- `ykt_driver.py`：Playwright 浏览器驱动（登录、抓题、作答、交卷、取分）
- `worker.py`：单任务执行流程
- `main.py`：入口，启动 N 个 Worker

## 启动

```powershell
cd g:\project\做题\agent
pip install -r requirements.txt
python -m playwright install chromium
$env:AGENT_COURSE="目标课程名"
python main.py
```

常用环境变量

- `BACKEND_BASE`（默认 `http://localhost:8080`）
- `MATCHER_BASE`（默认 `http://localhost:8100`）
- `COLLECTOR_BASE`（默认 `http://localhost:8200`）
- `AGENT_COURSE`：目标课程名，用于进入「我听的课」下的课程
- `AGENT_HEADLESS`：`1` 无头 / `0` 有头（首次调试建议 `0`）
- `AGENT_WORKER_COUNT`：Worker 数量，默认 1
- `AGENT_LOGIN_TIMEOUT`：扫码等待秒数，默认 180

## P0 说明

- `ykt_driver.py` 中带 TODO 的选择器（二维码、试卷列表、结果页得分）需首次真实登录后按页面 DOM 校准。
- 作答一律走「文本匹配 → 索引点击」，不按页面顺序猜字母，沿用 `docs\雨课堂答题AI提示词.md` 的铁律。
- 多账号并发（每账号独立浏览器上下文）为 P1，`WORKER_COUNT` 已预留。

状态：P0 骨架已完成（待真实页面校准选择器）
