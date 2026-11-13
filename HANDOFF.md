# 交接说明（HANDOFF）· 给下一个对话/协作者

> 用途：开新对话时把本文件发给 AI（或让它读 `G:\project\做题\HANDOFF.md`），即可无缝继续。
> 最后更新：2026-11-13（本地未提交改动见下节；上次同步 HEAD `d647efe`）

## 〇、2026-11-13 未提交改动（已全部改完，等用户验证/提交）

- **手机端整页不可滚动的根修**（Console/AnswerStage/PaperRail/ControlDeck/AdminLayout 五文件，vip 分支已同步同款）：
  桌面「各栏内滚」的 `flex:1+min-height:0` 压缩链在手机文档流里把面板压到 0 高（文档高度=视口高、内容裁没）。
  修法：≤768px 逐层解锁（容器 display:block + 面板/内滚 flex:none;height:auto;overflow:visible）。已 Playwright 390×844 实测可滚。
- **Agent「未找到开始答题按钮」无限报错循环根修**（worker.py/ykt_driver.py，vip 分支已同步同款）：
  已交卷 0 分空白卷且作答次数用完（详情页无 开始/再次答题 入口）→ ZERO_REDO 硬闯必失败 → 反复重派无限报错。
  修法：`state=="done" && !driver.has_entry_button()` → 改走 `_collect_from_result` 只读结果页采集入库，不再 FAILED；
  另加空白卷保险：重试点击后一道都没勾上（checked==0）拒绝交卷，保留作答次数。需在启动器重启 Agent 生效。


## 一、项目是什么

雨课堂**自动化做题平台**（仅供学习研究）：多用户网页工作台 + 浏览器 Agent 自动做题。
开新任务 → 扫码登录雨课堂 → 勾选卷子 → Agent 自动作答/交卷/采集答案入库 → 重做刷满分。

| 服务 | 端口 | 技术 |
|---|---|---|
| Frontend（做题台，唯一对外） | **25173**（占用时自动 25174~25192） | Vue3 + TS + Vite + Element Plus |
| Backend | 28080 | Spring Boot 3.2 + JPA + H2（backend/data/） |
| Matcher（题目-答案匹配） | 28100 | Python FastAPI（matcher/） |
| Collector（答案解析入库） | 28200 | Python FastAPI（collector/） |
| Agent（浏览器执行器） | 无 | Python + Playwright（agent/，驱动系统 Chrome） |

启动器：`launcher-rust/`（Rust + egui，`cargo build --release` → `target/release/ykt-launcher.exe`，约 3.4MB）。
旧 Python 启动器（launcher/）已删除。

## 二、这轮会话完成的重点（全部已提交并推送 GitHub）

### 邀请码体系（管理员后台 → 邀请码）
- 一码多人：生成时可设每码允许的注册人数（0=不限）；库表新增 `invite_use` 使用记录
- 邀请链接：`<域名>/login?invite=YKT-XXXX-XXXX` 自动切注册并填码（也兼容 `?code=`）
- 表格多选 + 批量禁用/启用/删除/复制链接；旧一码一用数据自动兼容

### 移动端与登录
- 手机端做题改为**四步向导**（①扫码登录→②勾选卷子→③自动做题→④查看结果），步骤自动推进
- 扫码阶段不再误跳第三步；二维码弹窗 `append-to-body` 不随面板隐藏
- 管理后台四个页面手机端改卡片列表；全局触控目标 ≥44px

### 做题系统（Agent/Matcher）—— 保住满分的几个关键修复
- **答案库按「题号」对齐**，绝不按数组下标（结果页缺一题会让后面全部错位，1915 曾因此 29 题低置信）
- **交卷**：等确认弹窗渲染（1.2s）再点；跳 `/cover/`（封面页）也算交卷成功；
  交卷后不在结果页时先 `open_result` 再采集；未跳转则如实报「交卷未成功」
- **登录检测**：登录页信号优先（URL `next=` / `img.logma`）；「已登录」要求应用壳 + 能读到账号名
  （SPA 清空登录后应用壳会闪一下，曾导致「扫码登录成功：None」跳过扫码）
- **同文本选项消歧**：结果页采集记录正确选项位置（答案带 `⟦pos:N⟧`），匹配遇「多个选项文字完全相同」按位置选
  （1915 第 17 题三个「百度」即此类）；`⟦pos:N⟧` 单独出现（正文为空）也支持按位置作答
- **结果页字母兜底**：题块没有正确标记时解析「正确答案：C」文字取选项；
  采集后如仍有缺题，日志明确列出题号
- 选项签名：内嵌链接/HTML 的选项附加 `⟦innerHTML/href/title⟧`，答题页与结果页同一规则
- 进入考试页竞态修复（轮询等「开始」按钮，不再「答题页未就绪」）；每套收尾提速（自适应等待+边等边推帧）

### 平台与启动器
- **端口迁移**：前端 25173 / 后端 28080 / matcher 28100 / collector 28200（避开热门端口与 Hyper-V 保留段）；
  被占用时自动向后扫描并联动 agent 地址与 vite 代理（`YKT_BACKEND_PORT`）
- **后端单实例保护**（H2 库同一时刻只允许一个后端）；端口占用者一律结束腾位（系统进程除外）
- 登录态跨重启：Agent 每个工人一个持久化浏览器 profile（`agent/runtime/profiles/w0…`），
  同账号续做免扫码；**切账号必须点「切换账号（重新扫码）」**（会清 Cookie/localStorage/IndexedDB）
- 内网穿透配置模板（tunnel/frpc.ini、frps.ini）**已按用户要求删除**
- 无界面自检：`ykt-launcher.exe --smoke`

## 二.5、2026-11-09 自 VIP 分支同步的通用改进（commit 2392b4c，已推 origin/main）

- 手机端布局根修：ConsoleLayout `.shell-body > *` 加 `min-width:0`（宽内容不再撑破整页）+ main.css 移动端 `overflow-x: clip` 兜底
- 接口限流 `RateLimitFilter`：登录/注册 20 次/分、下单 20/分、建任务 30/分（写方法，IP 滑动窗口，429）
- Agent 接口鉴权 `AgentTokenFilter`：`/api/agent/**`、`/api/answer/**` 支持 `X-Agent-Token`（backend 设 `ykt.agent-token`/环境变量 `YKT_AGENT_TOKEN`，agent 侧同名环境变量，两侧一致才放行；留空=不鉴权，当前默认关）
- H2 在线备份 `BackupService`：每日 03:00 `BACKUP TO` 到 `backend/backup/`（保留 7 份）；管理端手动触发 `POST /api/admin/backup`
- 调度开关 `@EnableScheduling`（备份的定时依赖）
- 以上均为平台级改进，无 VIP 付费逻辑；付费流程只在 VIP 分支（`做题-vip` worktree）

## 三、当前状态与待办

- 任务 129 已到 49/50；第 19 题答案已按用户截图**手动补入** `answer-lib/10-30/1915.md`（`- 答案：⟦pos:3⟧`），
  manifest 题数同步为 50。**待用户再重做一次验证 50/50**（若选项顺序被打乱则此题无解，属于题目本身缺陷）
- 位置消歧的前提「选项顺序稳定」已初步验证（q17 一轮后即选对），如个别题仍错，怀疑顺序被随机化，需另想办法
- 移动端还可继续精简：进度页的「运行详情」折叠块、「浏览器模式」切换（用户上一轮说还想再简化）
- `agent/runtime/`（profile/截图/日志）为运行期数据，已 gitignore，可随时清理

## 四、环境与常用操作（Windows）

- 后端编译**必须用 Java 21**：`JAVA_HOME="C:/Program Files/Zulu/zulu-21" mvn -DskipTests clean package`
  （后端运行时会锁 jar——先停后端再打包；启动器检测到 jar 过期会自动重建）
- 前端：`cd frontend && npm run build`（含 vue-tsc 类型检查）
- Agent/Matcher 是 Python：改完在启动器里点对应服务的「重启」
- 管理员账号在 `backend/config/local.yml`（**不入 git**，含 JWT 密钥，勿外传）
- 数据库：`backend/data/ykt.mv.db`（H2 文件库，运行时被锁；备份先停后端）
- 验证启动器：`ykt-launcher.exe --smoke`（真实拉起 5 服务并汇报，结束自动清理）
- git：本地 main 与 origin/main 同步；推送前检查是否夹带敏感文件（local.yml/data/logs 均已 gitignore）

## 五、踩过的坑（新对话务必先读）

1. H2 同一时刻只允许一个后端实例；两个后端会报 `Unable to determine Dialect` 崩溃
2. 端口探测不要用 connect（保留区间每个端口要等 300ms 超时）；用 bind 探测（瞬时返回）
3. `python -m uvicorn` 在 Windows 是父子进程结构，只杀登记 PID 会漏杀 → 停止时要按端口补刀
4. 前端轮询/等待循环的 catch 不要直接 stop——一次网络抖动就会永久停止（二维码「生成中」的根因）
5. 答案匹配一律按题号对齐；「多个选项文字相同」用 `⟦pos:N⟧` 位置消歧；`⟦…⟧` 标记是答案的一部分，比较时要先剥离
6. egui `add_sized`/尺寸计算必须 `.max(下限)`，首帧宽度为 0 会断言崩溃
7. README/PROJECT.md 是文档主源；`docs/`、`local.yml`、`data/`、`logs/` 不入库
