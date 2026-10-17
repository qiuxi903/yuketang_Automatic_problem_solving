# 项目详细细则（PROJECT.md）

> 本文档是平台的完整技术细则：架构、模块、流程、数据格式、配置与接口。配合根目录 [README.md](README.md) 阅读。
> 再次申明：本项目仅供学习与技术研究，请勿真实运用于雨课堂或任何在线学习平台。

## 1. 系统总览

单机部署的 5 服务协作架构，全部由启动器（或手动）拉起：

```
                       ┌─────────────────────────────┐
  浏览器 ──5173──▶      │ Frontend (Vue 3 + Vite)      │
                       │  /api 代理 ──8080──┐          │
                       └───────────────────┼──────────┘
                                           ▼
                       ┌─────────────────────────────┐
                       │ Backend (Spring Boot 3.2)    │  H2 文件库 backend/data/
                       │  REST + JWT + WebSocket      │
                       └───────┬───────────┬─────────┘
                    8100 ▼           8200 ▼
              ┌──────────────┐   ┌──────────────┐
              │ Matcher       │   │ Collector     │
              │ 题目-答案匹配  │   │ 答案解析入库   │──▶ answer-lib/（md 按日期批次）
              └──────────────┘   └──────────────┘
                       ▲ 轮询领任务 / 回传进度
                       └──────────────┐
                       ┌──────────────┴──────────────┐
                       │ Agent (Python + Playwright)  │ ──▶ 系统 Chrome/Edge
                       │ 1 调度器 + 3 执行工人         │     操作雨课堂页面
                       └─────────────────────────────┘
```

| 服务 | 端口 | 技术 | 职责 |
|---|---|---|---|
| Frontend | 5173 | Vue 3 + TS + Element Plus + Pinia | 工作台 UI、管理后台 |
| Backend | 8080 | Spring Boot 3.2 + JPA + H2 | 账号/邀请码/任务/会话/答案库/推送 |
| Matcher | 8100 | FastAPI | 题目文本与答案库的模糊匹配、置信度校验 |
| Collector | 8200 | FastAPI | 结果页答案解析、按批次落盘 answer-lib |
| Agent | — | Python + Playwright | 驱动真实浏览器完成 扫码/抓题/作答/交卷/采集 |

## 2. 模块细则

### 2.1 Backend（`backend/`）

**实体**：`User`（角色 USER/ADMIN，BCrypt 密码）、`InviteCode`（一码一用，可禁用/删除/批量清理）、`YktSession`（雨课堂扫码会话，状态机 `WAITING_SCAN → SCANNED → CLOSED`，二维码过期 `EXPIRED` 可刷新）、`Task`（任务）、`TaskItem`（任务明细）、`Paper` / `Question`（题目 DOM 快照）、`Contribution`（答案贡献记录）、`AnswerApiConfig`（兜底 API 配置）。

**状态机**：

- 任务 `TaskStatus`：`PENDING → RUNNING →（WAITING_BATCH 等用户确认批次）→ RUNNING → SUCCESS / FAILED`，另有 `PAUSED / CANCELLED`
- 明细 `TaskItemStatus`：`PENDING / ANSWERING / SUBMITTED（已交卷有分）/ COLLECTED（只采集了答案）/ REUSED（答案库已有直接复用）/ SKIPPED / FAILED`

**接口分区**（均以 `/api` 为前缀）：

| 分区 | 说明 |
|---|---|
| `/api/auth` | 注册（邀请码+强度校验）/ 登录（JWT） |
| `/api/sessions` | 创建扫码会话、二维码刷新、状态查询 |
| `/api/tasks` | 创建任务、批次确认（`/{id}/batch`，支持勾选清单与重做清单）、进度、导出、暂停/恢复/中断 |
| `/api/agent/**` | Agent 专用：领任务、回传 二维码/帧/题目/进度/答案/结果 |
| `/api/papers` `/api/answer` | 试卷清单（带雨课堂日期分组）、答案查询、兜底 API |
| `/api/community` | 答案社区贡献流 |
| `/api/admin/**` | 用户/角色、邀请码（生成/禁用/删除/清理已用）、任务、答案库、总览、兜底 API 设置 |

**推送**：WebSocket `/ws/tasks/{taskId}`，逐题进度、答题卡、实时帧（base64 截图缩略）都走这条通道。

**认证**：JWT（HS512），有效期默认 30 天（`ykt.jwt.expire-minutes`），角色拦截 `/api/admin/**`。CORS 放行任意来源（适配内网穿透域名不固定）。

### 2.2 Agent（`agent/`）

**并发模型**：1 个调度器轮询后端领任务 + N 个执行工人（默认 3，`AGENT_WORKER_COUNT`），每个工人独占一个浏览器上下文。

**两条铁律**：

1. 同一雨课堂账号同时只能有一个任务——占用注册表（内存态 + 过期自救），同账号任务自动排队；
2. 扫码后绝不自动开始做题，必须等用户在试卷清单勾选并点「做题 / 取答案」（`WAITING_BATCH` 确认通道，确认后回读任务快照把勾选清单贯通）。

**单卷执行流**（`_answer_one`）：

```
进入卷子 → 详情页状态检测（exam_entry_state）
  ├─ 未交卷 → 进答题页 → 抓题 → Matcher 匹配答案库 → 未命中题兜底 API → 逐题作答 → 交卷 → 读成绩
  ├─ 已交卷且有分数 → 防重复保险：不重复作答，只从结果页采集答案入库（COLLECTED）
  ├─ 已交卷但 0 分空白卷 → 自动「再次答题」重做一遍，交卷后引出答案（ZERO_REDO，可关）
  └─ 在重做清单（redoPapers）→ 无视保险强制重做刷分
答案库已有该卷 → 直接复用（REUSED），不重复获取
```

**页面操作**：`ykt_driver.py` 封装雨课堂页面（试卷列表按 DOM 顺序扫描日期分组标题、进入考试、等待答题页就绪、抓题、点选、交卷、读分、结果页采集、封面页自动点「查看试卷」）。选择器校准方法见仓库历史文档，参数集中在 `config.py`，全部可用环境变量覆盖（见 §5）。

### 2.3 Matcher（`matcher/`）

`POST /match`：题目干 + 选项 与答案库题目的模糊匹配（归一化 + 相似度打分），返回每题答案索引与置信度；低置信题在 Agent 侧打警告并由兜底 API 补答。`verify` 在作答后反查勾选结果与答案库的一致性。

### 2.4 Collector（`collector/`）

`POST /collect`：接收结果页解析出的题目+答案，写入 `answer-lib`。

**批次路由规则**（核心）：卷子已存在于某个批次 → 原批次原文件更新；新卷子 → 写入「当天日期」批次（如 `10-15/`），manifest 同步登记。因此答案库天然按采集日期分组，且不产生重复文件。

### 2.5 Frontend（`frontend/`）

- `Login.vue`：登录/注册切换，注册带邀请码 + 用户名/密码强度校验
- `Console.vue` 工作台三栏：左 试卷清单（按雨课堂日期分组、组头全选、新卷子组置底）/ 中 操作与实时进度（答题卡、题号进度、事件流、实时帧）/ 右 成绩与导出
- 任务结束自动识别「COLLECTED（取到答案但没做）」的卷子并弹窗询问是否一键重做（消耗一次作答次数）
- 管理后台：总览、用户角色、邀请码（统计 + 筛选 + 删除 + 一键清理已使用）、任务、答案库、兜底 API 设置

### 2.6 Launcher（`launcher/`）

Tkinter 单窗口深色启动器：5 个服务卡片独立 启动/停止/重启，状态点 + 端口/PID；日志分页签（「全部」汇总 + 各服务），按级别/时间戳分色、限行 1500、智能滚动；一键启动顺序为 matcher/collector/backend → 等 8080 HTTP 就绪 → frontend + agent；启动前自动清理端口上的本平台残留进程；5173 被系统保留时自动向后换端口（5173-5192）。`PYTHONIOENCODING=utf-8` 注入子进程保证中文日志不乱码。可用 PyInstaller 打包单文件 exe。

## 3. 答案库格式（`answer-lib/`）

```
answer-lib/
├── manifest.json          清单：batches[]（批次名、papers[]、generatedAt）
├── 09-24/                 批次 = 采集日期（月-日）
│   ├── 硬件2010.md        每卷一个 md 文件
│   └── ...
└── 10-15/
    └── 硬件2007.md
```

卷文件格式：

```markdown
# 硬件2007

> 批次：10-15　|　题量：50　|　成绩：50/50（已满分）

**1.** 题干……

- 答案：正确选项的完整文本
```

Collector 同时维护 `manifest.json`；Backend 启动时把整个答案库导入内存并定时校验。

## 4. 任务与答案的完整生命周期

```
用户勾选卷子点「做题/取答案」
→ Backend 创建任务（同平台账号单任务限制；同雨课堂账号排队）
→ Agent 调度器领任务 → 工人打开雨课堂登录页 → 用户扫码（会话可复用）
→ WAITING_BATCH：前端弹批次确认 → 用户确认（可改勾选清单/重做清单）
→ 逐卷执行（见 §2.2）→ 每题进度 WebSocket 推送
→ 交卷读分 / 结果页采集 → Collector 落盘 answer-lib（§2.4 路由规则）
→ 任务 SUCCESS，前端展示成绩汇总；COLLECTED 卷子弹窗询问是否重做
```

## 5. 配置项

**Backend**（`backend/src/main/resources/application.yml`，本地覆盖文件 `backend/config/local.yml` 不入库）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `ykt.jwt.secret` | 占位符 | JWT 密钥，**部署必改**（或环境变量 `YKT_JWT_SECRET`） |
| `ykt.jwt.expire-minutes` | 43200 | 登录态有效期（30 天） |
| `ykt.admin.username/password` | admin / 占位符 | 首次启动引导创建的管理员（或 `YKT_ADMIN_PASSWORD`） |
| `ykt.answer-lib.dir` | ../answer-lib | 答案库目录 |

**Agent**（环境变量，`agent/config.py`）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_COURSE` | 空 | 课程名（启动器里可填，重启 Agent 生效） |
| `AGENT_WORKER_COUNT` | 3 | 执行工人数 |
| `AGENT_BROWSER_CHANNEL` | chrome | playwright 浏览器通道（chrome / msedge） |
| `AGENT_HEADLESS` | 1 | 是否无头（0 = 显示浏览器窗口） |
| `AGENT_ZERO_REDO` | 1 | 0 分空白卷自动重做引答案 |
| `AGENT_BATCH_WAIT_TIMEOUT` | 600 | 等待用户确认批次的超时（秒） |
| `AGENT_LOGIN_TIMEOUT` | 180 | 等待扫码超时（秒） |

## 6. 本地开发注意

- 后端 jar 运行时锁定文件：Windows 下重新 `mvn package` 前先停后端
- H2 数据库在 `backend/data/`，结构变更用 H2 Shell 前必须停后端（锁库），操作前备份到 `backup/`
- 前端 `/api` 由 Vite 代理到 8080；内网穿透只暴露 5173，**不要**直接暴露 8080
- 邀请码在管理后台生成；注册用户名不可纯数字、密码需 8 位以上且含字母和数字

## License

MIT，详见 [LICENSE](LICENSE)。
