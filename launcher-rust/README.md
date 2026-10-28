# 雨课堂启动器（Rust 版）

单窗口亮色启动器（Windows 11 Fluent 风格：白卡片大圆角、柔和描边与投影、强调蓝 #0067c0、Segoe UI / Cascadia 字体），用 Rust + [egui](https://github.com/emilk/egui) 重写（替代旧版 Python + Tkinter，见 `../launcher/`）：
**单文件 exe、约 3.4 MB、无需安装 Python 环境即可使用。**

## 功能（与 Python 版对齐）

- 一个窗口管全部 5 个服务：`matcher(28100) / collector(28200) / backend(28080) / frontend(25173) / agent`（刻意选不常用端口段，避免与热门端口上的程序互相干扰）
- **左侧服务列表 + 右侧日志**的双栏布局：每个服务行内嵌 启动/停止/重启 按钮，点击行即切换右侧日志视图；状态点 + 端口 / PID 实时展示
- 扁平亮色设计：白面板细描边、无投影、Win11 强调蓝、Segoe UI / Cascadia 字体
- **一键启动**：matcher/collector/backend 立即拉起 → 等 8080 HTTP 就绪 → 再起 frontend 与 agent
- 日志分页签（「全部」汇总 + 各服务）：按级别分色（ERROR 红 / WARN 黄 / INFO 蓝）、时间戳紫色、
  限行 1500 行、智能滚动（向上翻历史即暂停跟随，点「恢复」回到底部）
- 启动前自动清理端口上的**本平台残留进程**（按命令行特征识别，外来程序不动）
- **端口治理（全部服务）**：端口被占用时一律结束占用者腾位（平台残留静默清理，外来程序也结束但在日志里写明 PID 与进程名）；端口被 Hyper-V/WSL 保留区间吞掉（bind 10013，杀无可杀）时向后扫描，选中的端口联动注入 agent（MATCHER_BASE/COLLECTOR_BASE/BACKEND_BASE）与 vite 代理（YKT_BACKEND_PORT），整条链路保持互通
- 无界面自检：`ykt-launcher.exe --smoke` 真实拉起全部服务并汇报（用于验证环境，结束后自动清理）
- 后端 jar 缺失或比源码旧时自动 `clean package` 重建；前端 node_modules 缺失自动 `npm install`
- **后端单实例保护**：H2 数据库文件同一时刻只允许一个后端，启动前检测到已有后端在运行则直接接管（记录端口/PID），绝不启动第二个；日志里出现 H2 库锁报错时自动补一条人话提示
- 子进程隐藏窗口运行，注入 `PYTHONIOENCODING=utf-8` 保证中文日志不乱码
- 关闭窗口即停止全部服务（有服务在跑时先弹确认）；退出前兜底清理子进程

## 构建

```powershell
cd launcher-rust
cargo build --release
# 产物：target\release\ykt-launcher.exe（双击即用，可复制到任意位置，
# 启动时会自动向上查找同时含 backend 与 agent 子目录的项目根）
```

依赖 Rust 1.78+（仅编译期需要；运行期零依赖）。

## 代码结构

| 文件 | 职责 |
|---|---|
| `src/main.rs` | 入口：定位项目根、装配 Core 与 channel、启动窗口 |
| `src/proc.rs` | 系统工具：端口探测、netstat 解析、kill_tree、Java 定位、HTTP 就绪检测 |
| `src/core.rs` | 服务定义与生命周期：构建命令（自动 mvn/npm）、进程泵、一键启动编排 |
| `src/logs.rs` | 日志模型：等级识别、时间戳前缀解析、1500 行限行 |
| `src/app.rs` | UI：Win11 亮色扁平主题、左侧服务栏、日志面板、退出确认、字体加载 |

> Java 21 运行时会自动挑选（JAVA_HOME → Zulu 21 → JDK 21 → PATH），后端 jar 用 `--server.port` 指定漂移后的端口。
