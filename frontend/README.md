# frontend 前端

技术栈：Vue 3 + Vite + TypeScript + Element Plus + Pinia + Vue Router + axios

状态：P0 骨架已完成（可 `npm run build` 通过，`npm run dev` 起本地开发服务器）

## 如何启动

```bash
cd frontend
npm install
npm run dev          # 开发模式，http://localhost:5173
```

其他命令

```bash
npm run build        # 类型检查（vue-tsc）+ 生产构建，产物在 dist/
npm run preview      # 预览构建产物
npm run type-check   # 只做类型检查
```

## 联调约定

- 端口 5173，`vite.config.ts` 已配置代理，前端只发相对路径请求：
  - `/api` → `http://localhost:8080`
  - `/ws` → `ws://localhost:8080`（WebSocket 升级）
- 因此本地联调需先启动 backend（8080），再启动前端。
- 接口路径、字段、状态枚举严格遵循 `docs/接口契约.md`，前端不做改名。

## 目录结构

```
src/
  api/            axios 实例与接口封装（所有请求都走这里，不在页面里散落 fetch）
    client.ts       axios 实例：自动带 Authorization、401 跳登录、统一错误提示
    auth.ts         /api/auth/login、/api/auth/register
    session.ts      /api/sessions 增查删
    paper.ts        /api/papers、/api/papers/{batch}/{name}/answers
    task.ts         /api/tasks 增查、pause / resume
  composables/
    useTaskSocket.ts  /ws/tasks/{id} 订阅 Task 快照，断线指数退避重连
  layouts/
    MainLayout.vue    顶栏 + 侧边栏 + 内容区
  router/index.ts   路由与登录守卫（未登录跳 /login）
  store/
    auth.ts          token / username，持久化到 localStorage
    task.ts          当前任务快照
  types/index.ts    Task / Paper / Session 等类型，字段与契约一致
  views/
    Login.vue         登录 / 注册切换
    TaskCreate.vue    选择批次与模式，创建任务后跳转进度页
    TaskProgress.vue  状态、进度、二维码、试卷明细、WebSocket 实时刷新
    Papers.vue        批次与试卷清单（只读）
```

## 页面流程

1. `/login` 登录或注册平台账号，成功后 token 存 Pinia + localStorage，跳 `/tasks/create`
2. `/tasks/create` 从 `GET /api/papers` 取批次列表，选择 answer / collect 模式；
   提交时先 `POST /api/sessions` 拿 sessionId，再 `POST /api/tasks` 创建任务，跳 `/tasks/{id}`
3. `/tasks/{id}` 展示任务状态、总进度（donePaper/totalPaper）、当前试卷与题号、累计得分；
   二维码区展示会话二维码并每 3s 轮询 `GET /api/sessions/{id}`，扫码成功后提示；
   试卷明细表格展示每套试卷的名称、状态、得分、错误；
   同时用 WebSocket 订阅 `/ws/tasks/{id}` 接收 Task 快照实时刷新，断线自动重连
4. `/papers` 只读展示各批次的试卷清单与答案库文件

## 待办（P1 预留点）

- 任务列表页：`GET /api/tasks` 已封装（`api/task.ts` 的 `listTasks`），尚无页面
- 答案库在线查看 / 编辑：`GET /api/papers/{batch}/{name}/answers` 已封装，尚无页面
- 二维码自动刷新倒计时、会话过期自动重建会话
- WebSocket 心跳与重连状态提示细化（当前仅顶部标签）
- 生产构建的 chunk 拆分（Element Plus 目前为全量引入，主包约 1.1MB）
