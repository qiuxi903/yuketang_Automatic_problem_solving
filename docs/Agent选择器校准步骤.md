# Agent 选择器校准步骤

`agent\ykt_driver.py` 的页面选择器已于 2026-10-03 在真实账号上完成校准（课程「高二理论」、试卷「硬件2010」，examId 4624095）。本文件保留校准结论与可复用的探针用法，后续页面改版时按同样方法重校。

## 一、校准结论（最终选择器）

登录态

- 已登录判据：页面出现「我听的课」或「我教的课」
- 二维码：登录页优先取 `img[src*='qrcode']`，依次回退 `img[src*='qr']`、`canvas`、`.qrcode`、`.login-qrcode`、`.qrcode-img`、`[class*='qrcode'] img`，末位兜底整页截图

课程入口

- 路径：入口页点「我听的课」→ 点课程卡文本（如「高二理论」）→ 落在学习日志页
- 登录后可能弹「近期新增功能」引导框，需先关闭含「我知道了 / 知道了 / 跳过」的弹窗

学习日志试卷卡片

- 卡片容器：`div.activity-box`
- 试卷名：卡片内 `h2`
- 满分与题量：`.sub-info`，形如「满分：50分 共50题」
- 得分：`.bigWord`
- 状态：`.statistics-box`，含「得分」表示已答，含「继续答题 / 未作答」表示未完成
- 卡片不含 examId，也没有 href 或 data 属性，必须点开卡片后从地址栏解析

考试详情页与进入答题

- 点卡片跳转到 `/v2/web/exam/{courseId}/{examId}`（同标签页导航，不是弹窗）
- 详情页按钮：「开始答题」（未答）或「再次答题」（已答）
- 点该按钮在新标签页打开考前提示页 `/start/{examId}`，点「开始」后进入答题页 `/exam/{examId}?isFrom=2`
- 注意：部分入口的详情页文案是「再次作答」，代码里已一并兼容

答题页与结果页题目块（两页结构一致）

- 题目块：`.item-body`（内含题干与选项）
- 题干：`h4.exam-font`
- 选项：`label.el-radio`，选项文字 `.radioText`
- 已勾选：`label.el-radio.is-checked`
- 结果页正确项：`.dot-success` 所在 `label` 内的 `.radioText` 即正确答案文本

重要修正：题目块不能再用 `div.exam-font`。实测中 `div.exam-font` 是**选项列表容器**（内含 `ul` 与 `label.el-radio`），题干 `h4.exam-font` 在它**外面**，用它会抓不到题干。

## 二、探针用法

在 `g:\project\做题\agent` 目录执行。探针用持久化登录态目录 `agent\runtime\profile`，扫码一次后续复用。

```
python probe.py login
python probe.py course --course "高二理论"
python probe.py logs --course "高二理论"
python probe.py open --course "高二理论" --paper "硬件2010"
python probe.py open --course "高二理论" --paper "硬件2010" --action view
python probe.py answer --exam-id 4624095
python probe.py result --exam-id 4624095
```

各步骤作用

- `login`：确认登录态与二维码选择器
- `course`：确认课程入口点击目标
- `logs`：解析学习日志试卷卡片，打印名称、满分、题量、得分、状态
- `open`：点开指定试卷，打印详情页地址并解析 courseId 与 examId；加 `--action view` 进结果页，加 `--action retry` 进答题页
- `answer`：直接访问答题页，打印题目块与选项结构
- `result`：直接访问结果页，打印题目块、正确项文本与得分

浏览器通道默认用系统 Chrome（`--channel chrome`），本机无法下载 Playwright Chromium 时用它可以正常跑。

## 三、校准后的验证

已用真实登录态跑通单账号完整答题链路，结果如下

- `list_papers` 解析出 20 套试卷，名称、满分、题量、得分、是否已答均正确
- `open_paper("硬件2010")` 解析出 courseId=32909007、examId=4624095
- 抓题 50 道，答案库文本匹配 0 未匹配，点击 50 个、已勾选 50，交卷得分 50/50
- 结果页采集 50 条正确项文本，`read_score` 返回 50/50

整套服务联调（backend 8080 + matcher 8100 + collector 8200 + agent）时，用有头模式跑一遍：

```
$env:AGENT_HEADLESS="0"
$env:AGENT_COURSE="高二理论"
python main.py
```

## 四、页面改版时的重校位置

- `is_logged_in()`：登录态选择器
- `capture_qrcode()`：二维码选择器
- `ykt_name()`：账号名。真实页面右上角只有头像，账号名在悬停气泡 `.userInfoPopover p.name`（常态 `display:none`，节点仍在 DOM），另可从 `localStorage` 的 `vuex` → `userInfo.name` 或 `Report.Daily.User` → `name` 读取
- `list_courses()` / `open_course()`：课程卡 `.lesson-cardS`，课程名在 `.left .top h1`，`course_name` 为空时自动取首门课
- `list_papers()`：试卷卡片解析（`div.activity-box`，仅保留含「满分 / 共N题」的考试类活动）
- `open_paper()`：点卡片后解析 courseId 与 examId
- `enter_exam()`：详情页按钮与提示页「开始」
- `QUESTION_BLOCK`、`STEM`、`OPTION`、`OPTION_TEXT`、`OPTION_CHECKED`：答题页选择器常量
- `read_score()`、`collect_correct_answers()`：结果页取分与正确项解析

## 五、账号信息校准记录（2026-10-05）

用真实登录态（账号「雷智宸」，课程「高二理论」，班级「高二25计1」）重新校准：

- 入口页右侧仅有头像，旧的 `.user-name / .nickname / .avatar-name` 全部取不到 → 面板曾一直显示「待扫码」
- 改由 `.userInfoPopover p.name` 读取（原始文本「你好，雷智宸」，需去掉前缀「你好，」），并保留 localStorage 兜底 → `ykt_name()` 返回 `雷智宸`
- 课程卡 `.lesson-cardS` → 课程名 `高二理论`；点击后落到 `/v2/web/studentLog/{classroom_id}`，`classroom_id` 即课程 ID（32909007）
- 学习日志页 `div.activity-box` 共 140 个，其中考试类 20 个；按「满分 / 共N题」过滤后 `list_papers()` 返回 20 套

