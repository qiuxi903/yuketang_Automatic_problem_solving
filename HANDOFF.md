# 交接说明（HANDOFF）· VIP 分支

> 用途：开新对话时把本文件发给 AI（或让它读 `G:\project\做题\做题-vip\HANDOFF.md`），即可无缝继续。
> 最后更新：2026-11-09（VIP 分支，基于 main `d647efe`）
> **main 分支的交接说明在 `G:\project\做题\HANDOFF.md`（未入库），两份各自维护。**

## 一、本分支是什么

雨课堂自动化做题平台的 **VIP 收费版**（开源）：在免费版之上加付费体系。
免费版（main）继续免费做题；本分支所有批次需要解锁：单日付费永久解锁 / 月卡期内无限 /
免费额度 / 兑换码，并强制雨课堂账号 1:1 绑定。

**分支隔离**：主目录 `G:\project\做题`（main）零改动；本 worktree 在 `G:\project\做题\做题-vip`，
已写入主目录 `.git/info/exclude`（`/做题-vip/`），不会被 git 进 main。
运行数据独立：H2 库 `backend/data/ykt-vip.mv.db`（main 是 `ykt.mv.db`）；同一时刻只跑一个版本的后端（同端口）。

## 二、VIP 功能与实现位置（全部已提交并推送 origin/VIP）

### 付费模型
- 单日解锁：`VipEntitlement(kind=DATE, targetBatch)`，一次付费永久；月卡：`kind=MONTH, expiresAt`，续费顺延
- 订单：`VipOrder`，应付 = 价格 − 最优折扣(活动 vs 折扣码) + 随机尾数(1~99分，监听渠道)；10 分钟有效期
- 核销路径：监听回调自动（`POST /api/pay/notify`，listenToken 校验）/ 管理员确认 / 粘贴收款文本快速核销 / 虎皮椒（骨架 NOT RUN）
- 免费额度：`VipAccount.freeQuota`，注册赠 `ykt.vip.free-quota-on-register`(默认1)，`POST /api/vip/unlock-quota` 消耗
- 优惠码 `VipCoupon` 四类：DISCOUNT（下单填，与活动取最优）/ DATE_PASS（兑换选日期）/ MONTH_PASS / QUOTA；perUserLimit 防刷

### 闸门（402）
- **收费边界：只有自动做题（mode=answer）收费**；取答案（collect）与答案中心浏览/导出永远免费（Community/Answer/Paper 控制器零 VIP 引用）
- `TaskService.create/setBatch/claim`：`VipService.checkBatchAccess(..., mode)` → 未解锁且 answer 模式抛 `VipRequiredException` → `VipExceptionHandler` 返 **402** `{code:"VIP_REQUIRED", batch, datePassPriceFen, monthPriceFen}`
- `agentNext/claim` 兜底再核；ADMIN 放行；`ykt.vip.enforce=false` 全局关闭（开源用户可当免费版跑）
- 前端 `api/client.ts` 拦 402 → 跳 `/vip?batch=日期`（VipPage 预选）

### 雨课堂绑定（防帮做）
- 扫码登录回传 yktName → `SessionService.updateStatus` → `VipService.onScanLogin`：首扫自动 1:1 绑定（`RainBinding`）；
  不符/被他绑 → 会话置 **REJECTED** + message；session-status 接口回传 `AgentSessionStatusResponse`，agent 收到 REJECTED 即中止任务
- worker.py：读不到账号名不再「12s 放行」（fail-closed），等待到超时明确报错
- 管理后台可解绑；`ykt.vip.bind-required=false` 关闭

### 按日期定价
- 新表 `vip_batch_price`（batch 唯一, priceFen）：管理员单独定价某日期，未覆盖的日期用 `VipSettings.datePassPriceFen`（默认 3 元）
- 月卡默认价已改 15 元（VipSettings 默认 1500 分；存量库由 VipBootstrap 从旧默认 2900 一次性迁移，不覆盖管理员改过的价）
- API：`GET/PUT/DELETE /api/admin/vip/batch-prices`；下单/商品接口（`/api/vip/products` 的 VipBatchView.priceFen）与 402 payload（batchPriceFen）全部跟随批次价
- 管理端「支付与活动」页：定价 panel 下方「单日按日期定价」表（改价/保存/恢复默认，单位元）；付费页日期下拉显示每个日期的价格并联动价格卡
- 管理端价格输入一律用「元」，展示/保存换算分（曾出过把分当元显示的 bug，见 3f）

### 运营统计
- **申报核实（到账记录池）**：监听/粘贴的每笔收款先入 `pay_notification` 池（未匹配订单也不丢）；
  用户点「我已支付，核实到账」→ 按实付金额精确比对池中未核销记录 → 符合即核销记录并自动开通（verified=true）；
  不符合 → 提示并保持待支付（轮询继续，到账后自动开通）。管理端订单页有「最近到账记录」卡（含未核销，可删误报）
- `GET /api/admin/vip/stats`：累计/今日收入、近 7 日收入序列、订单分布、月卡在期人数、单日解锁人次、额度总量、优惠码可用/核销（内存聚合）
- AdminVip 订单页顶部统计条（含纯 CSS 迷你柱图）；AdminOverview 首卡「VIP 收入」（AdminOverviewResponse 加 vipRevenueFen/vipPendingOrders/vipMonthActiveUsers）
- 付费页 hero 显示已解锁日期 chips（前 3 + N）；支付弹层倒计时到期自动停轮询并提示重新下单

### UI 统一原则（后续改版必须遵守）
- 全站设计语言 = 设计系统 v2：page-head（eyebrow + h2 + page-sub + 按钮组）、白卡片 stat-card/panel-card、
  主色晴空蓝 CTA、琥珀色只做「金额数字 / VIP 小标记」点缀；**禁止大面积品牌渐变色块**（初版金色 hero 已按用户要求重做成统一版式）
- 付费页 /vip 与管理后台/概览页同构：page-inner 版心（max-width 1020）+ stat-grid 四卡 + 定价卡（推荐=主色蓝边+蓝角标）
- 金额颜色规则：琥珀 --amber（VipPage 价格/实付、AdminVip payable、支付弹层应付大字）

### 支付页 / 运营后台
- `/vip`（VipPage.vue）：金色 hero + 权益状态 + 双定价卡（活动划线价）+ 收款码支付弹层（精确金额/倒计时/3s轮询/成功态）+ 优惠码 + 兑换中心 + 免费额度一键解锁 + 订单记录；暗色/移动端适配
- `/admin/vip`（AdminVip.vue）四页签：订单管理（确认/拒单/粘贴核销）、优惠码（批量生成四类码）、支付与活动（价格/渠道/收款码上传/监听令牌/限时活动）、用户权益（发月卡/调额度/解绑/撤销）

### pay-listener（电脑端免签监听）
- `pay-listener/listener.py`：策略 uia（读微信窗口控件树，零注入）→ ocr（RapidOCR 截图识别）自动降级；wcf 可选默认关
- `--selftest` 金额解析已验证；**`--probe-uia` 本机校准未做**（需要用户微信在前台跑一次，见其 README）
- 配置 `config.ini`（gitignore）：backend_url + 监听令牌（后台「支付与活动」页可查）

## 三、已验证 / 未验证（诚实清单）

- ✅ `mvn clean package`（Java 21）通过；`npm run build`（vue-tsc）通过；agent py 编译通过
- ✅ 端到端冒烟 **24/24**（含月卡默认 1500 断言、批次价覆盖/下单/恢复默认）（含 collect 免费、统计口径、概览摘要、申报未核实拒绝、到账入池申报自动开通）（`python -I docs/vip-smoke.py`）：管理员登录→邀请码注册（赠额度1）→单日订单→监听回调核销→权益生效→错误令牌403→402闸门→免费额度解锁→任务放行→兑换码（限一次）→月卡订单→管理员确认→月卡生效
- ✅ 浏览器视觉验证：/vip 亮色/暗色/移动端(375px) + 支付弹层移动端 + /admin/vip 移动端卡片视图，UI 与设计系统 v2 统一（var() 令牌）
- ❌ NOT RUN：虎皮椒渠道联调（无凭据）；pay-listener 对真实微信收款的校准（`--probe-uia` 待用户跑）；
  雨课堂真实扫码的绑定全链路（需真人扫码终验）；暗色/移动端只过了代码层（var() 令牌+断点），未截图细验

## 四、环境与常用操作

- 后端必须 Java 21：`JAVA_HOME="C:/Program Files/Zulu/zulu-21" mvn -DskipTests clean package`；
  运行也要用 21 的 java（PATH 上是 17，直接 `java -jar` 会 UnsupportedClassVersionError）
- VIP 库全新时管理员是默认 admin/admin123（主目录的 local.yml 不在 worktree 里，需要的话从 `G:\project\做题\backend\config\local.yml` 复制过来——注意两分支 JWT secret 不同会互不认登录态，无所谓）
- **worktree 里没有 answer-lib**（gitignored）：跑后端前从主目录 `cp -r ../answer-lib .`，否则批次列表为空
- 冒烟：`python -I docs/vip-smoke.py`（后端起来后跑，幂等，用户名按时间戳随机）
- git：本地 VIP ↔ origin/VIP 已同步；主目录 main 零提交零改动（worktree 布局见上）

## 五、踩过的坑（继承 main 全部条目 + 新增）

1. `VipOrder.status` 是 String 不能标 `@Enumerated`（Hibernate AnnotationException）——用常量就别用枚举注解
2. 后端 jar 必须用 Zulu 21 的 java 跑，PATH 的 17 跑不了
3. worktree 缺 answer-lib → `/api/vip/products` batches 为空 → 付费页日期下拉是空的
3b. **Windows 下运行中的 jar 被锁定：`mvn clean package` 会静默失败继续跑旧 jar**——先停后端（含孤儿 java 进程，`netstat -ano | grep 28080` 查 PID 再 taskkill）再打包
3c. 前端 dev server 是父子进程结构，TaskStop/杀父进程会留孤儿 vite 占 25173（服务旧代码白屏/旧页面）——按端口查 PID `taskkill //PID <pid> //F //T` 连子进程一起杀
3d. **手机端整页排版变宽/内容右缘被裁的根因**：ConsoleLayout `.shell-body > *` 是 flex 子项，默认 min-width:auto 下会被宽内容（el-table 最小内容宽等）撑破布局宽度——已加 `min-width:0` 修复；新增布局必须保证 flex 链上每级都有 min-width:0
3f. 金额单位换算：后端全程「分」，管理端输入/展示「元」——新增价格类 UI 时务必换算（单日定价表曾把 300 分显示成 300 元）
3e. AdminVip 四个 el-tab-pane 已加 `lazy`：非激活页签不渲染 DOM，避免隐藏容器里 el-table 按内容宽度初始化（同时首屏更轻）；main.css 移动端有 `html,body{overflow-x:clip}` 兜底，页面不应再出现横向滑动
4. GBK 控制台打印 ✓/✗ 会 UnicodeEncodeError——脚本里 `sys.stdout.reconfigure(encoding="utf-8")` 或 `PYTHONIOENCODING=utf-8`
5. el-table 模板槽 `{ row }` 是 any，直接 `map[row.x]` 会 vue-tsc TS7053——包一层类型化辅助函数
6. main 的坑全部继承：H2 单实例、端口 bind 探测、uvicorn 按端口补刀、轮询 catch 不要 stop、答案按题号对齐、egui 尺寸下限等（见 main HANDOFF）

## 六、待办

- 用户侧终验：真实扫码走一遍「未解锁→402→付费页→支付→自动到账→做题」全链路
- pay-listener `--probe-uia` 本机校准（微信 3.x/4.x 控件树差异），确认后挂任务计划常驻
- 上传真实收款码（后台），按需调整价格（默认单日 ¥3 / 月卡 ¥29）
- 虎皮椒：若要启用，注册拿凭据填 local.yml → 小额实测回调 → 后台切渠道
- 移动端付费页/后台可再细调（暗色模式未截图验证）
