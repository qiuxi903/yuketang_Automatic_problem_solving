# VIP 分支说明（收费版 · 开源）

> 本分支在免费版（`main`）基础上加入 **付费做题体系**。main 保持免费版不动，两分支各自独立演进；
> VIP 分支同样开源。本地开发用 git worktree：`G:\project\做题\做题-vip`（已加入主目录 `.git/info/exclude`，不会混入 main）。

## 功能总览

| 功能 | 说明 |
|---|---|
| 单日解锁 | 按答案库批次（日期）付费，一次付费永久解锁该日期全部卷子 |
| 月卡 | `monthDays`（默认 30）天内所有日期无限做题，续费自动顺延 |
| 免费额度 | 新用户注册赠 `ykt.vip.free-quota-on-register`（默认 1）次，可解锁任意一个日期；后台可加/减 |
| 优惠码 | 四类：折扣码（下单用）/ 单日兑换码 / 月卡兑换码 / 额度码；可当卡密挂发卡平台销售 |
| 限时活动 | 窗口期内下单自动折扣，与优惠码取最优、不叠加 |
| 雨课堂绑定 | 平台账号 ↔ 雨课堂账号 1:1，首次扫码自动绑定，防帮他人做题；解绑需管理员 |
| 付费页 | `/vip`：定价卡、收款码支付弹层（倒计时+轮询）、兑换中心、订单记录 |
| 运营后台 | `/admin/vip`：订单核销、优惠码、支付与活动设置、用户权益与绑定管理 |

## 收款渠道（后台可切换，`LISTEN` 为推荐主力）

### LISTEN · 免签监听（自动到账，零手续费零资质零备案）
1. 后台上传个人微信/支付宝**收款码**，设置单日/月卡价格；
2. 部署 `pay-listener/`（电脑端监听工具，详见其 README）；
3. 用户下单 → 付费页显示「价格 − 折扣 + 随机尾数(1~99分)」的精确金额 → 扫个人收款码支付；
4. 微信电脑版收到收款消息 → 监听器回调 `/api/pay/notify?token=监听令牌` → 订单自动核销、权益秒到账。

兜底链：监听漏单 → 后台「粘贴收款文本快速核销」→「确认到账」按钮。

### MANUAL · 人工确认
用户按金额转账 + 点「我已完成付款」，管理员在订单管理点「确认到账」。

### XUNHU · 虎皮椒（骨架已留，NOT RUN）
`local.yml` 填 `YKT_XUNHU_APPID` / `YKT_XUNHU_SECRET` 后，后台把渠道切到 XUNHU 即启用；
回调 `/api/pay/notify/xunhu` 已按官方 MD5 验签规则实现，**未实际联调**，开通后需先小额实测。

## 配置（backend application.yml → ykt.vip）

```yaml
ykt.vip:
  enforce: true                  # 付费闸门总开关；false = VIP 分支按免费版跑（开源用户体验友好）
  bind-required: true            # 雨课堂 1:1 绑定开关
  free-quota-on-register: 1      # 注册赠送免费解锁次数
  xunhu-appid / xunhu-secret     # 虎皮椒凭据（留 local.yml，不入库）
```

数据隔离：VIP 分支 H2 库为 `backend/data/ykt-vip.mv.db`，与 main 的 `ykt.mv.db` 互不干扰。
管理员引导账号与 JWT 密钥同 main（`config/local.yml` 覆盖，不入库）。

## 本地开发

```powershell
# 主目录（main，免费版）完全不动；VIP 在 worktree 里开发
cd G:\project\做题\做题-vip
# 后端（Java 21）
cd backend; JAVA_HOME="C:/Program Files/Zulu/zulu-21" mvn -DskipTests clean package
"C:/Program Files/Zulu/zulu-21/bin/java" -jar target/ykt-platform-0.0.1-SNAPSHOT.jar
# 前端
cd frontend; npm install; npm run dev     # http://localhost:25173
# 端到端冒烟（16 项，后端起来后跑）
python -I docs/vip-smoke.py
```

注意：同一台机器**不要同时**跑 main 与 VIP 的后端（同端口 28080，启动器会互杀）；
VIP 版会话/任务/订单数据全在 `ykt-vip` 库，互不串。

## 端到端链路（闸门位置）

- 用户确认批次（`POST /api/tasks/{id}/batch`）或带批次建任务时 → `VipService.checkBatchAccess`
  → 月卡在期 / 该批次有 DATE 权益 → 放行；否则 **HTTP 402** `{code:"VIP_REQUIRED", batch, ...}`
- 前端 axios 拦截器统一捕 402 → 弹提示跳 `/vip?batch=日期`（付费页自动预选该日期）
- Agent 领取任务前再核一次（权益被撤销的兜底）；管理员角色不受限
- 扫码登录回传账号名 → `VipService.onScanLogin`：未绑定自动 1:1 绑定；不符/被他绑 → 会话 REJECTED，任务中止并展示原因

## 与 main 的差异清单

后端：`vip/` 包（402 异常+advice）、`entity/`+8（VipSettings/VipActivity/VipOrder/VipEntitlement/VipCoupon/VipCouponUse/RainBinding/VipAccount）、`service/VipService`+`VipAdminService`、`controller/`+3（Vip/AdminVip/PayNotify）、`config/VipProperties`+`VipBootstrap`、TaskService/SessionService/AuthService/SecurityConfig/SessionStatus 小改、`application.yml`（ykt-vip 库 + ykt.vip.*）。
前端：`views/VipPage.vue`、`views/admin/AdminVip.vue`、`api/vip.ts`、client.ts 402 拦截、router、ConsoleLayout（VIP 徽章+tabbar）、AdminLayout 菜单、store/console.ts（REJECTED 事件）。
其它：`pay-listener/`（电脑端监听）、`docs/vip-smoke.py`（端到端冒烟，不入库）。
collector / matcher / launcher-rust 无改动。
