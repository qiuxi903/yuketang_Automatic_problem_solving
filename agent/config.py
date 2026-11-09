"""agent 运行配置。全部可用环境变量覆盖，便于容器与多实例部署。"""

import os

BACKEND_BASE = os.environ.get("BACKEND_BASE", "http://localhost:28080")
# Agent 内部接口鉴权令牌：与 backend 的 YKT_AGENT_TOKEN 一致时才能调用 /api/agent/**（后端留空 = 不鉴权）
AGENT_TOKEN = os.environ.get('YKT_AGENT_TOKEN', '')
# 同机部署零配置：后端未配环境变量时自动读取其 data/agent-token 令牌文件
if not AGENT_TOKEN:
    try:
        _token_file = os.path.join(os.path.dirname(__file__), '..', 'backend', 'data', 'agent-token')
        with open(_token_file, encoding='utf-8') as f:
            AGENT_TOKEN = f.read().strip()
    except OSError:
        AGENT_TOKEN = ''
MATCHER_BASE = os.environ.get("MATCHER_BASE", "http://localhost:28100")
COLLECTOR_BASE = os.environ.get("COLLECTOR_BASE", "http://localhost:28200")

HEADLESS = os.environ.get("AGENT_HEADLESS", "1") not in ("0", "false", "False")
SLOW_MO = int(os.environ.get("AGENT_SLOW_MO", "0"))

COURSE_NAME = os.environ.get("AGENT_COURSE", "")

# 浏览器通道：留空用 Playwright 自带 Chromium；本机无法下载 Chromium 时可指定 chrome 或 msedge
BROWSER_CHANNEL = os.environ.get("AGENT_BROWSER_CHANNEL", "chrome")

POLL_INTERVAL = float(os.environ.get("AGENT_POLL_INTERVAL", "1"))

# 已交卷但 0 分（空白卷）且答案库无答案的卷子：自动「再次答题」重做一遍以引出正确答案（消耗 1 次作答）
ZERO_REDO = os.environ.get("AGENT_ZERO_REDO", "1") not in ("0", "false", "False")
# 执行工人数 = 可并行的雨课堂账号数（每个工人独占一个浏览器）
WORKER_COUNT = int(os.environ.get("AGENT_WORKER_COUNT", "3"))

PAPER_GAP = float(os.environ.get("AGENT_PAPER_GAP", "0.8"))
LOGIN_TIMEOUT = int(os.environ.get("AGENT_LOGIN_TIMEOUT", "180"))

# 批次后置：登录并回传账号作业清单后，等待用户确认批次的最长秒数
BATCH_WAIT_TIMEOUT = float(os.environ.get("AGENT_BATCH_WAIT_TIMEOUT", "600"))

# 浏览器模式：实时截图回传（前端可看到雨课堂原始页面）。节流间隔避免刷屏请求。
FRAME_ENABLED = os.environ.get("AGENT_FRAME", "1") not in ("0", "false", "False")
FRAME_INTERVAL = float(os.environ.get("AGENT_FRAME_INTERVAL", "1"))

YKT_INDEX = "https://www.yuketang.cn/v2/web/index"
YKT_LOGOUT = "https://www.yuketang.cn/pc/web_logout"

USER_AGENT = os.environ.get(
    "AGENT_UA",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
)
