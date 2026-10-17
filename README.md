# 雨课堂自动化做题平台（Yuketang Automatic Problem Solving）

> 📖 **项目详细细则（架构 / 模块 / 流程 / 数据格式 / 配置 / 接口）见 [PROJECT.md](PROJECT.md)**

> [!WARNING]
> ## ⚠️ 免责声明（必读）
>
> - 本项目**仅供学习与技术研究使用**，请勿将本项目真实运用于雨课堂或任何在线学习/考试平台。
> - 使用自动化工具代做作业、考试属于**违反平台规则与学术诚信**的行为，一切后果由使用者自行承担。
> - 本项目作者不鼓励、不支持任何形式的学术不端行为；仓库内容仅用于演示 Web 自动化、多服务协作架构等工程技术。
> - 请在下载后 24 小时内删除，如坚持使用造成账号封禁、成绩作废或其他任何损失，与本项目无关。

一个多用户「做题工作台」全栈项目：学生注册平台账号后扫码登录雨课堂，勾选要处理的试卷，由浏览器 Agent 自动完成作答 / 答案采集，答案自动入库（按采集日期分批次），前端实时展示进度、成绩与答案。

本项目由 AI 编写（Vibe Coding）。

## 功能特性

- **多用户平台**：平台账号注册（邀请码制、密码强度校验）、JWT 登录、管理员后台（用户 / 邀请码 / 任务 / 答案库 / 设置）
- **扫码登录雨课堂**：Agent 自动打开雨课堂登录页，二维码过期自动刷新，支持会话复用（不用每次扫码）
- **自动做题**：答案库命中的题目自动作答，未命中回退兜底 API，逐题实时回报进度，交卷后自动读取成绩
- **答案采集入库**：已交卷的试卷自动从结果页采集答案；答案按「采集日期」分批次存放，自动路由（已入库卷子原批次更新，新卷子进当天批次）
- **智能分流**：已交卷且库中有答案的卷子直接复用；0 分空白卷自动「再次答题」重做以引出答案；同账号任务自动排队
- **可视化工作台**：试卷清单（按雨课堂日期分组）、答题卡式进度、实时事件流、成绩汇总、答案导出
- **单窗口启动器**：Tkinter 深色主题 GUI，5 个服务独立 启动 / 停止 / 重启，日志按级别 / 时间戳分色，PyInstaller 可打包为单文件 exe

## 技术栈

| 模块 | 技术 |
|---|---|
| 前端 | Vue 3 + TypeScript + Vite + Element Plus + Pinia |
| 后端 | Java 21 + Spring Boot 3.2 + Spring Security(JWT) + JPA + H2(文件库，可切 MySQL) + WebSocket |
| Agent | Python 3.10+ + Playwright（驱动系统 Chrome/Edge） |
| 匹配服务 | Python + FastAPI（题目-答案模糊匹配与校验） |
| 采集服务 | Python + FastAPI（结果页答案解析入库） |
| 启动器 | Python + Tkinter，PyInstaller 打包 |

## 环境要求

- Windows 10/11（Agent 的页面选择器按雨课堂桌面端 Web 校准）
- Python 3.10+（matcher / collector / agent / launcher）
- Node.js 18+（前端；本机用 22 验证）
- JDK 21 + Maven 3.9+（后端）
- Chrome 或 Edge 浏览器（Agent 通过 playwright channel 复用系统浏览器，无需另下 chromium）

## 部署方法

### 1. 克隆与依赖

```powershell
git clone https://github.com/qiuxi903/yuketang_Automatic_problem_solving.git
cd yuketang_Automatic_problem_solving

# 前端依赖
cd frontend && npm install && cd ..

# Python 依赖（matcher / collector / agent 各有 requirements.txt）
pip install -r matcher\requirements.txt
pip install -r collector\requirements.txt
pip install -r agent\requirements.txt
```

### 2. 配置密钥（重要）

后端密钥与管理员密码**不进版本库**，首次部署必须自己配置，二选一：

- 写 `backend/config/local.yml`（会被自动加载并覆盖默认值，且已被 .gitignore 忽略）：

```yaml
ykt:
  jwt:
    secret: "换成一段足够长的随机字符串"
  admin:
    username: admin
    password: "换成你的管理员密码"
```

- 或用环境变量 `YKT_JWT_SECRET` / `YKT_ADMIN_PASSWORD` 覆盖。

### 3. 启动

**方式 A：启动器（推荐）**

```powershell
cd launcher
python launcher_app.py
# 或用 PyInstaller 打成单文件 exe：
python -m PyInstaller --noconfirm --onefile --noconsole --name 雨课堂启动器 launcher_app.py
```

打开后点「一键启动」：Matcher(8100) / Collector(8200) / Backend(8080) 立即拉起，后端 HTTP 就绪后自动起 Frontend(5173) 与 Agent。每个服务可独立 启动 / 停止 / 重启。

**方式 B：手动逐个启动**

```powershell
cd matcher;   python -m uvicorn app:app --port 8100
cd collector; python -m uvicorn app:app --port 8200
cd backend;   mvn -DskipTests package; java -jar target\ykt-platform-0.0.1-SNAPSHOT.jar
cd frontend;  npm run dev
cd agent;     python main.py
```

启动后访问 <http://localhost:5173>，用管理员账号登录（后台生成邀请码 → 新用户凭邀请码注册）。

### 4. 使用流程

1. 登录工作台 → 发起任务 → 手机扫码登录雨课堂（账号不固定，谁扫做谁）
2. 确认答案库批次 → 勾选试卷清单里的卷子 → 点「做题」（拿分 + 答案自动入库）或「取答案」（只采集）
3. 前端实时看答题卡进度 / 题号进度 / 成绩；完成后可导出作答结果
4. 已交卷的空白 0 分卷会自动重做一遍引出答案；「取到答案但未做」的卷子会弹窗询问是否一键重做刷分

### 5. 内网穿透（可选）

只穿透前端 5173 即可（前端自带 `/api` 代理到后端，后端 8080 不要直接暴露）。HTTP 类穿透（ngrok / cloudflared 等）或 TCP 类（frp）均可；穿透域名会变，后端 CORS 已放行任意来源。

## 目录结构

```
├── PROJECT.md     项目详细细则（架构/模块/流程/数据格式/配置/接口）
├── answer-lib/    答案库（按日期批次存放的 md 答案文件 + manifest.json）
├── launcher/      单窗口启动器（Python + Tkinter，可打包 exe）
├── frontend/      前端 Vue 3 + Vite + Element Plus
├── backend/       后端 Spring Boot 3.2（JPA + JWT + WebSocket，H2 文件库）
├── agent/         做题执行器（Python + Playwright 驱动真实浏览器）
├── matcher/       题目-答案匹配与校验服务（FastAPI）
├── collector/     答案采集服务（FastAPI）
└── docker-compose.yml  容器编排（MySQL/Redis 为 P1 预留）
```

## 核心链路

```
前端发起任务 → 后端落库入队 → 任务队列 → 空闲 Agent 领取
→ Playwright 驱动浏览器操作雨课堂（抓题 → 匹配答案 → 作答 → 交卷 → 采集答案）
→ 结果与进度回写 → WebSocket 推送 → 前端实时展示；答案自动写入 answer-lib
```

## License

本项目基于 [MIT License](LICENSE) 开源。
