# backend 后端

技术栈：Spring Boot 3.2.5 + Java 21 + Spring Security(JWT) + Spring Data JPA + WebSocket
（P0 用 H2 内存库与内存队列，MySQL / Redis 已预留）

状态：已完成 P0 骨架（可编译、可启动、核心接口已跑通冒烟测试）

职责

- 平台账号体系与鉴权（JWT，BCrypt 存密码）
- 雨课堂会话管理（会话记录与状态机，二维码由 Agent 回传）
- 答案库读取（manifest.json 与 md 解析）与试卷题目导入
- 任务编排与队列调度，Agent 轮询领取
- WebSocket 推送任务进度快照

## 如何启动

前置：JDK 21、Maven 3.9+。项目默认 H2 内存库，无需额外依赖。

```powershell
cd backend
$env:JAVA_HOME="C:\Program Files\Zulu\zulu-21"   # 本机 Maven 默认 JDK 17，需切到 21
mvn spring-boot:run
```

服务端口 8080，H2 控制台 `http://localhost:8080/h2-console`
（JDBC URL `jdbc:h2:mem:ykt`，用户名 `sa`，空密码）。

编译验证：

```powershell
mvn -q -DskipTests compile
```

切 MySQL（P1，需自行准备库与账号，当前不要求可连通）：

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

## 配置项（application.yml）

| 配置 | 说明 |
| --- | --- |
| `ykt.jwt.secret` | JWT 密钥，长度需 ≥ 32 字节；P1 改为环境变量注入 |
| `ykt.jwt.expire-minutes` | 令牌有效期，默认 1440 分钟 |
| `ykt.answer-lib.dir` | 答案库目录，默认 `../answer-lib`（相对 backend 运行目录） |

## 接口一览（详见 `../docs/接口契约.md`）

- 认证：`POST /api/auth/register`、`POST /api/auth/login`
- 会话：`POST /api/sessions`、`GET /api/sessions/{id}`、`DELETE /api/sessions/{id}`
- 试卷：`GET /api/papers`、`GET /api/papers/{batch}/{name}/answers`
- 任务：`POST /api/tasks`、`GET /api/tasks`、`GET /api/tasks/{id}`、`POST /api/tasks/{id}/pause`、`POST /api/tasks/{id}/resume`
- Agent 回传：`GET /api/agent/tasks/next`、`POST /api/agent/tasks/{id}/qrcode|session-status|progress|finish`
- WebSocket：`ws://localhost:8080/ws/tasks/{id}`，任务状态或进度变化时推送 Task 快照 JSON

鉴权：除 `/api/auth/**`、`/api/agent/**`、`/ws/**`、`/h2-console/**` 外均需 `Authorization: Bearer <token>`。

## 目录结构

```
src/main/java/com/ykt/platform
  config       安全、CORS、WebSocket、配置属性
  security     JWT 生成/解析、认证过滤器
  entity       User/YktSession/Paper/Question/Task/TaskItem 及状态枚举
  repository   Spring Data JPA 仓储
  dto          请求/响应 record
  service      Auth/Session/AnswerLib/Task + TaskQueue 抽象与内存实现、调度器
  controller   Auth/Session/Paper/Task/Agent
  ws           TaskWebSocketHandler
src/main/resources
  application.yml / application-mysql.yml
```

## P1 预留点（代码内以 `P1` 注释标出）

- `TaskQueue` / `InMemoryTaskQueue`：换 Redis 队列与常驻 Worker；`TaskDispatcher` 由对账循环升级为失败重试与超时回收
- `YktSession.sessionKey`：雨课堂 Cookie 需加密存储，当前仅占位
- `TaskService.pause`：需通过控制通道通知正在执行的 Agent
- Agent 内部接口：`/api/agent/**` P0 不校验令牌，P1 增加内部鉴权
- `AnswerLibService.importOnStartup`：改为采集闭环触发的增量同步
- `ykt.jwt.secret`：改为环境变量/密钥管理注入
