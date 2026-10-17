"""agent 入口：启动 N 个 Worker 轮询 backend 任务队列。

用法：
    python main.py
环境变量见 config.py。
"""

import logging
import logging.handlers
import os
import threading
import time

import config
from worker import Dispatcher, Worker

# 日志同时输出到控制台（启动器捕获）与 agent\runtime\agent.log（滚动，保留 2 个备份）。
# 启动器窗口关闭后控制台日志即丢，文件日志用于事后排查「做失败了」这类问题。
RUNTIME_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "runtime")
os.makedirs(RUNTIME_DIR, exist_ok=True)

_console = logging.StreamHandler()
_file = logging.handlers.RotatingFileHandler(
    os.path.join(RUNTIME_DIR, "agent.log"),
    maxBytes=5 * 1024 * 1024,
    backupCount=2,
    encoding="utf-8",
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
    handlers=[_console, _file],
)


def main():
    workers = []
    for i in range(config.WORKER_COUNT):
        w = Worker(name="w%d" % i)
        t = threading.Thread(target=w.run_forever, name=w.name, daemon=True)
        t.start()
        workers.append(w)
        time.sleep(3)  # 错峰启动：多个 Chrome 同时冷启动会相互干扰导致启动失败
    # 调度器在主线程：唯一领任务入口，把任务派给各工人（多账号并行的核心）
    Dispatcher(workers).run_forever()


if __name__ == "__main__":
    main()
