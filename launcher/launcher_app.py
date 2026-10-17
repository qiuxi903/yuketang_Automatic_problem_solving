# -*- coding: utf-8 -*-
"""
雨课堂自动化做题平台 · 启动器 v2（单窗口可视化管理）

一个 exe 管全部服务：matcher / collector / backend / frontend / agent。
- 不弹服务窗口：所有子进程隐藏运行，日志汇总到各页签（UTF-8，无乱码）
- 每个服务独立 启动 / 停止 / 重启，也可一键启动全部
- 双击即用：自动定位项目根目录；后端 jar / 前端依赖缺失时先自动准备再启动
- 日志限行 + 智能滚动：只在滚动条位于底部时跟随，翻历史时不会被强拉到底
- 关闭窗口即停止全部服务

打包：python -m PyInstaller --noconfirm --onefile --noconsole --name 雨课堂启动器 launcher_app.py
"""

import os
import queue
import re
import shutil
from http.client import HTTPConnection
import socket
import subprocess
import sys
import threading
import time
import webbrowser
import tkinter as tk
from pathlib import Path
from tkinter import messagebox, ttk

# ---------------------------------------------------------------- 定位与工具

def find_root() -> Path:
    """从 exe（打包后）或本文件（源码运行）向上找同时含 backend 与 agent 子目录的根。"""
    candidates = []
    if getattr(sys, "frozen", False):
        candidates.append(Path(sys.executable).resolve().parent)
    else:
        candidates.append(Path(__file__).resolve().parent)
    candidates.extend(Path.cwd().resolve().iterdir() if Path.cwd().is_dir() else [])
    for start in candidates[:2]:
        for d in [start, *start.parents]:
            if (d / "backend").is_dir() and (d / "agent").is_dir():
                return d
    raise RuntimeError("未找到项目根目录（需同时存在 backend 与 agent 子目录）")


ROOT = find_root()


def port_open(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


def bind_ok(port: int) -> bool:
    """端口能否绑定。Windows 的 Hyper-V/WSL 动态端口保留会让 bind 报权限错误（EACCES），
    此时端口没人监听但谁都绑不上——5173 常常中招，必须探测后换端口。"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False


def port_pid(port: int):
    """返回监听该端口的进程 PID（无则 None）。"""
    try:
        out = subprocess.run(
            f'netstat -ano -p tcp | findstr ":{port} "',
            shell=True, capture_output=True, text=True, encoding="gbk", errors="replace",
            creationflags=subprocess.CREATE_NO_WINDOW,
        ).stdout
    except Exception:
        return None
    for line in out.splitlines():
        if "LISTENING" in line:
            try:
                return int(line.split()[-1])
            except ValueError:
                continue
    return None


def cmdline_of(pid: int) -> str:
    """读进程命令行；失败返回空串。"""
    try:
        return subprocess.run(
            "powershell -NoProfile -Command \"(Get-CimInstance Win32_Process -Filter 'ProcessId=" + str(pid) + "').CommandLine\"",
            shell=True, capture_output=True, text=True, encoding="gbk", errors="replace",
            creationflags=subprocess.CREATE_NO_WINDOW, timeout=15,
        ).stdout or ""
    except Exception:
        return ""


OURS_MARKERS = ("ykt-platform", "vite.js", "app:app", "main.py", "雨课堂启动器", "做题")


def looks_ours(cmd: str) -> bool:
    return any(m in (cmd or "") for m in OURS_MARKERS)


def clear_port_owner(port: int, svc_log, tag: str) -> bool:
    """端口被占时：若占用者是本平台的残留进程则强制清除并返回 True；
    是别的程序则不动（日志里给出 PID 与进程名，由用户自行处理）。"""
    pid = port_pid(port)
    if pid is None:
        return True  # 已无监听
    cmd = cmdline_of(pid)
    if looks_ours(cmd):
        svc_log("warn", f"端口 {port} 被平台残留进程占用（PID {pid}），自动清除…")
        kill_tree(pid)
        time.sleep(1)
        svc_log("ok", f"残留进程已清除，端口 {port} 可用")
        return not port_open(port)
    svc_log("warn", f"端口 {port} 被其它程序占用（PID {pid}：{cmd[:60] or '未知'}），"
                    "不属于本平台，未清除；请手动处理")
    return False


def backend_ready() -> bool:
    """后端 Spring 是否已可响应 HTTP（能拿到任意响应即算就绪，不要求 200）。
    用 POST 探测 /api/auth/login（该接口只收 POST），避免每次启动都在后端日志里
    留一条 GET-not-supported 的 WARN。"""
    try:
        conn = HTTPConnection("127.0.0.1", 8080, timeout=1)
        conn.request("POST", "/api/auth/login", body="{}",
                     headers={"Content-Type": "application/json"})
        conn.getresponse().read()
        conn.close()
        return True
    except Exception:
        return False


def resolve_python() -> str:
    """服务的 Python 解释器：与系统 python 同环境（依赖装在这里）。"""
    return shutil.which("python") or sys.executable


def resolve_java() -> str:
    """后端按 Java 21 编译：在候选里实际测版本，选第一个 21+ 的 java；都没有再退回 PATH。"""
    candidates = []
    home = os.environ.get("JAVA_HOME")
    if home:
        candidates.append(Path(home) / "bin" / "java.exe")
    candidates += [
        Path(r"C:\Program Files\Zulu\zulu-21\bin\java.exe"),
        Path(r"C:\Program Files\Java\jdk-21\bin\java.exe"),
    ]
    path_java = shutil.which("java")
    if path_java:
        candidates.append(Path(path_java))
    seen = []
    for cand in candidates:
        if cand.is_file() and str(cand) not in seen:
            seen.append(str(cand))
    for cand in seen:
        if java_major(cand) >= 21:
            return cand
    return seen[0] if seen else "java"


def java_major(java_exe: str) -> int:
    try:
        out = subprocess.run(
            [java_exe, "-version"], capture_output=True, text=True, timeout=10
        ).stderr
        for part in out.replace('"', " ").split():
            if part[:1].isdigit():
                return int(part.split(".")[0])
    except Exception:
        pass
    return 0


def kill_tree(pid: int) -> None:
    subprocess.run(
        ["taskkill", "/PID", str(pid), "/T", "/F"],
        capture_output=True,
        text=True,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )


# ---------------------------------------------------------------- 服务定义

class Service:
    def __init__(self, key, title, port, builder):
        self.key = key
        self.title = title
        self.port = port          # agent 无端口，置 None
        self.builder = builder    # 返回 (argv, cwd, env)；已就绪时返回 SKIP_START
        self.proc = None
        self.status = "未启动"
        self.widget = None        # 日志 Text

    # —— 进程控制 ——
    def alive(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def stop(self, announce=True):
        if self.proc is not None:
            kill_tree(self.proc.pid)
            self.proc = None
        if announce:
            self.log("info", "已停止")
            self.status = "已停止"

    def start(self, extra_env=None):
        if self.alive():
            self.log("warn", "已在运行，无需重复启动")
            return
        if self.port and port_open(self.port):
            # 端口被占：平台残留进程 → 自动清除后照常启动；外来程序 → 跳过并说明
            if clear_port_owner(self.port, self.log, self.key):
                pass  # 已清空，继续正常启动
            else:
                self.status = "端口被占"
                return
        try:
            built = self.builder(self)
        except Exception as exc:
            self.status = "启动失败"
            self.log("danger", f"准备启动失败：{exc}")
            return
        if built is SKIP_START:
            return  # builder 已说明原因（如服务已在运行）
        if built is None:
            # builder 内部已把失败原因写进日志（如依赖安装失败），这里不再重复启动
            if self.status in ("未启动",):
                self.status = "启动失败"
            return
        argv, cwd, env = built
        if env is None:
            env = os.environ.copy()
        else:
            merged = os.environ.copy()
            merged.update(env)
            env = merged
        env["PYTHONUNBUFFERED"] = "1"
        # 中文 Windows 上 Python 子进程默认按 GBK 写管道，这里强制 UTF-8，读取端才不乱码
        env["PYTHONIOENCODING"] = "utf-8"
        if extra_env:
            env.update(extra_env)
        try:
            self.proc = subprocess.Popen(
                argv,
                cwd=str(cwd),
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
                creationflags=subprocess.CREATE_NO_WINDOW,
            )
        except Exception as exc:
            self.status = "启动失败"
            self.log("danger", f"启动失败：{exc}")
            return
        self.status = "运行中"
        self.log("ok", f"已启动（PID {self.proc.pid}）")
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        proc = self.proc
        try:
            for line in proc.stdout:
                self.log(None, line.rstrip())
        except Exception:
            pass
        code = proc.wait()
        if self.proc is proc:
            self.proc = None
            if self.status != "已停止":
                self.status = f"已退出({code})"
                self.log("danger" if code else "info", f"进程退出，code={code}")

    # —— 日志 ——
    def log(self, level, text):
        queue_log.put((self.key, level, text))


queue_log = queue.Queue()


# —— 各服务的启动参数（准备缺失产物后给出直接子进程命令，避免嵌套 shell） ——

def build_matcher(svc):
    return (
        [resolve_python(), "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8100"],
        ROOT / "matcher",
        None,
    )


def build_collector(svc):
    return (
        [resolve_python(), "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8200"],
        ROOT / "collector",
        None,
    )


def _ensure_backend_jar(svc):
    jars = list((ROOT / "backend" / "target").glob("*.jar")) if (ROOT / "backend" / "target").is_dir() else []
    jars = [j for j in jars if not j.name.endswith(".original")]
    if jars:
        return jars[0]
    svc.status = "构建中"
    svc.log("warn", "未找到后端 jar，先执行 mvn -DskipTests package（首次较慢，请等待）…")
    java = resolve_java()
    home = Path(java).parent.parent if Path(java).name.lower() == "java.exe" else None
    env = os.environ.copy()
    if home and home.is_dir():
        env["JAVA_HOME"] = str(home)
    build = subprocess.run(
        "mvn -q -DskipTests package",
        cwd=str(ROOT / "backend"),
        env=env,
        shell=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    tail = (build.stdout or "") + (build.stderr or "")
    for line in tail.strip().splitlines()[-12:]:
        svc.log(None, line)
    if build.returncode != 0:
        raise RuntimeError(f"后端打包失败（code={build.returncode}），见上方输出")
    jars = [j for j in (ROOT / "backend" / "target").glob("*.jar") if not j.name.endswith(".original")]
    if not jars:
        raise RuntimeError("打包完成但未找到 jar")
    return jars[0]


def build_backend(svc):
    try:
        jar = _ensure_backend_jar(svc)
    except Exception as exc:
        svc.log("danger", str(exc))
        svc.status = "构建失败"
        return None
    java = resolve_java()
    if java_major(java) < 21:
        svc.log("warn", f"当前 java 版本低于 21（{java}），后端可能无法启动；建议安装 Zulu 21 并设置 JAVA_HOME")
    svc.log("info", f"使用 {java}")
    return (
        [java, "-jar", str(jar)],
        ROOT / "backend",
        None,
    )


SKIP_START = object()  # builder 返回值：服务已在运行/无需启动，start() 直接返回


def resolve_frontend_port(svc):
    """为前端选端口：优先 5173；被系统保留（bind EACCES）则依次向后找可用端口。
    返回 (port, already_running)。"""
    for port in range(5173, 5193):
        if port_open(port):
            svc.port = port
            return port, True  # 已有前端在跑（可能是上次启动留下的）
        if bind_ok(port):
            svc.port = port
            if port != 5173:
                svc.log("warn", f"端口 5173 被系统保留（Hyper-V/WSL 动态端口），前端改用 {port}")
            return port, False
    return None, False


def build_frontend(svc):
    vite = ROOT / "frontend" / "node_modules" / "vite" / "bin" / "vite.js"
    if not vite.is_file():
        svc.status = "安装依赖中"
        svc.log("warn", "缺少 node_modules，先执行 npm install（仅首次）…")
        inst = subprocess.run(
            "npm install",
            cwd=str(ROOT / "frontend"),
            shell=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        for line in ((inst.stdout or "") + (inst.stderr or "")).strip().splitlines()[-8:]:
            svc.log(None, line)
        if inst.returncode != 0:
            svc.log("danger", "npm install 失败")
            svc.status = "安装失败"
            return None
    port, running = resolve_frontend_port(svc)
    if running:
        svc.log("info", f"前端已在端口 {port} 运行，无需重复启动")
        svc.status = "已在运行"
        return SKIP_START
    if port is None:
        svc.log("danger", "5173-5192 端口均不可用，前端无法启动")
        svc.status = "启动失败"
        return None
    node = shutil.which("node") or "node"
    return ([node, str(vite), "--port", str(port), "--strictPort"], ROOT / "frontend", None)


def build_agent(svc):
    # Agent 通过 playwright 的 channel 机制直接使用系统浏览器（Chrome/Edge，见 agent\config.py
    # 的 BROWSER_CHANNEL，默认 chrome），无需下载 playwright 自带的 chromium。
    return ([resolve_python(), "main.py"], ROOT / "agent", None)


SERVICES = [
    Service("matcher", "Matcher · 匹配服务", 8100, build_matcher),
    Service("collector", "Collector · 采集服务", 8200, build_collector),
    Service("backend", "Backend · 后端 (Spring Boot)", 8080, build_backend),
    Service("frontend", "Frontend · 前端 (Vite)", 5173, build_frontend),
    Service("agent", "Agent · 做题执行器", None, build_agent),
]


# ---------------------------------------------------------------- 界面（深色主题）

C_BG = "#0f172a"       # 窗口底
C_BAR = "#0b1220"      # 顶栏 / 底栏
C_CARD = "#141d2e"     # 服务卡片
C_LOG = "#0b1020"      # 日志底色
C_TEXT = "#e2e8f0"
C_MUT = "#8296b3"
C_LINE = "#23324f"
C_ACC = "#34d399"      # 主按钮 / 运行中
C_ACC_BG = "#113b2d"
C_DANGER = "#f87171"
C_DANGER_BG = "#3d1a1a"
C_BTN = "#22304d"
C_BTN_H = "#2d3f63"
C_WARN = "#fbbf24"
FONT = "Microsoft YaHei UI"
MONO = "Consolas"

GOOD_STATUSES = ("运行中", "已启动", "已在运行")
BUSY_STATUSES = ("构建中", "安装依赖中", "准备浏览器中")
BAD_STATUSES = ("构建失败", "安装失败", "准备失败", "启动失败")


def status_color(st: str) -> str:
    if st in GOOD_STATUSES:
        return C_ACC
    if st in BUSY_STATUSES:
        return C_WARN
    if st in BAD_STATUSES or st.startswith("已退出"):
        return C_DANGER
    if st == "端口被占":
        return "#fb923c"
    return "#64748b"  # 未启动 / 已停止


class ServiceCard(tk.Frame):
    """单个服务的卡片：状态点 + 名称/端口/PID + 独立 启动/停止/重启。"""

    def __init__(self, master, app, svc: Service):
        super().__init__(master, bg=C_CARD, highlightbackground=C_LINE, highlightthickness=1)
        self.svc = svc
        self.app = app

        head = tk.Frame(self, bg=C_CARD)
        head.pack(fill="x", padx=12, pady=(10, 0))
        self.dot = tk.Canvas(head, width=10, height=10, bg=C_CARD, highlightthickness=0)
        self.dot.pack(side="left")
        self.dot_oval = self.dot.create_oval(1, 1, 9, 9, fill="#64748b", outline="")
        short = svc.title.split(" ·")[0]
        tk.Label(head, text=short, bg=C_CARD, fg=C_TEXT,
                 font=(FONT, 11, "bold")).pack(side="left", padx=6)
        self.meta = tk.Label(head, text="", bg=C_CARD, fg=C_MUT, font=(MONO, 9))
        self.meta.pack(side="right")

        self.state = tk.Label(self, text="未启动", bg=C_CARD, fg="#64748b",
                              font=(FONT, 9), anchor="w")
        self.state.pack(fill="x", padx=14, pady=(0, 4))

        row = tk.Frame(self, bg=C_CARD)
        row.pack(fill="x", padx=10, pady=(2, 10))
        for col, (text, handler, fg, bg) in enumerate([
            ("启动", app.start_one, C_ACC, C_ACC_BG),
            ("停止", app.stop_one, C_DANGER, C_DANGER_BG),
            ("重启", app.restart_one, C_TEXT, C_BTN),
        ]):
            tk.Button(row, text=text, cursor="hand2", relief="flat", bd=0,
                      bg=bg, fg=fg, activebackground=C_BTN_H, activeforeground=C_TEXT,
                      font=(FONT, 9), padx=6, pady=3,
                      command=lambda h=handler, s=svc: h(s)).grid(row=0, column=col, padx=3, sticky="ew")
            row.columnconfigure(col, weight=1)

    def refresh(self):
        st = self.svc.status
        color = status_color(st)
        self.dot.itemconfigure(self.dot_oval, fill=color)
        self.state.configure(text=st, fg=color)
        port = f":{self.svc.port}" if self.svc.port else ""
        pid = f"  PID {self.svc.proc.pid}" if self.svc.proc else ""
        self.meta.configure(text=(port + pid).strip())


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        root.title("雨课堂启动器 · 雨课堂自动化做题平台")
        root.geometry("1120x720")
        root.minsize(940, 600)
        root.configure(bg=C_BG)
        self._setup_style()

        self.course_var = tk.StringVar(value=os.environ.get("AGENT_COURSE") or "高二理论")
        # 自动打开浏览器默认关闭：用户手动点「打开做题台」；若勾选，也会等后端+前端都就绪才打开
        self.auto_open = tk.BooleanVar(value=False)
        self._opened_browser = False
        self.cards = {}

        self._build_toolbar()
        self._build_cards()
        self._build_logs()
        self._build_statusbar()
        self._wire_close()

        self.root.after(150, self._drain_logs)
        self.root.after(1000, self._refresh)

        # 手动启动：不自动开服务、不自动开浏览器，由用户点「一键启动」或单卡「启动」

    # —— 样式 ——
    def _setup_style(self):
        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("TNotebook", background=C_BG, borderwidth=0, tabmargins=(10, 8, 10, 0))
        style.configure("TNotebook.Tab", background=C_BTN, foreground=C_MUT,
                        padding=(16, 7), borderwidth=0, font=(FONT, 10))
        style.map("TNotebook.Tab",
                  background=[("selected", C_CARD)],
                  foreground=[("selected", C_TEXT)])
        style.configure("Vertical.TScrollbar", background=C_BTN, troughcolor=C_LOG,
                        bordercolor=C_LOG, arrowcolor=C_MUT, relief="flat")

    # —— 顶部工具栏 ——
    def _build_toolbar(self):
        bar = tk.Frame(self.root, bg=C_BAR)
        bar.pack(fill="x")
        tk.Label(bar, text="雨课堂启动器", bg=C_BAR, fg=C_TEXT,
                 font=(FONT, 13, "bold")).pack(side="left", padx=16, pady=10)

        def btn(text, cmd, kind="normal"):
            colors = {
                "primary": (C_ACC, "#052e1f", C_ACC),
                "danger": (C_DANGER, C_DANGER_BG, C_DANGER),
                "normal": (C_TEXT, C_BTN, C_TEXT),
            }[kind]
            tk.Button(bar, text=text, command=cmd, cursor="hand2", relief="flat", bd=0,
                      bg=colors[1], fg=colors[0], activebackground=C_BTN_H,
                      activeforeground=C_TEXT, font=(FONT, 10), padx=14, pady=5,
                      highlightthickness=0).pack(side="left", padx=4, pady=8)

        btn("一键启动", self.start_all, "primary")
        btn("全部停止", self.stop_all, "danger")
        btn("打开做题台", self.open_ui)
        tk.Checkbutton(
            bar, text="就绪后自动打开浏览器", variable=self.auto_open,
            bg=C_BAR, fg=C_MUT, selectcolor=C_BAR,
            activebackground=C_BAR, activeforeground=C_TEXT,
            highlightthickness=0, bd=0, font=(FONT, 9),
        ).pack(side="left", padx=8)

        course = tk.Frame(bar, bg=C_BAR)
        course.pack(side="right", padx=12)
        tk.Label(course, text="课程名", bg=C_BAR, fg=C_MUT, font=(FONT, 9)).pack(side="left")
        e = tk.Entry(course, textvariable=self.course_var, width=10, bd=0,
                     bg=C_CARD, fg=C_TEXT, insertbackground=C_TEXT,
                     font=(FONT, 10), justify="center")
        e.pack(side="left", padx=6, ipady=3)
        tk.Label(course, text="（重启 Agent 生效）", bg=C_BAR, fg="#556481",
                 font=(FONT, 8)).pack(side="left")

    # —— 服务卡片区 ——
    def _build_cards(self):
        strip = tk.Frame(self.root, bg=C_BG)
        strip.pack(fill="x", padx=10, pady=(10, 0))
        for i, svc in enumerate(SERVICES):
            card = ServiceCard(strip, self, svc)
            card.grid(row=0, column=i, sticky="nsew", padx=4)
            strip.columnconfigure(i, weight=1)
            self.cards[svc.key] = card

    # —— 日志页签 ——
    def _build_logs(self):
        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill="both", expand=True, padx=10, pady=(8, 4))

        self.all_text = self._make_log_widget("全部")
        for svc in SERVICES:
            svc.widget = self._make_log_widget(svc.title.split(" ·")[0])

    def _make_log_widget(self, tab_name: str) -> tk.Text:
        frame = tk.Frame(self.notebook, bg=C_LOG)
        self.notebook.add(frame, text=tab_name)
        text = tk.Text(
            frame, wrap="none", state="disabled", bg=C_LOG, fg="#cbd5e1",
            insertbackground="#cbd5e1", font=(MONO, 10), relief="flat", bd=0,
        )
        scroll = ttk.Scrollbar(frame, command=text.yview, style="Vertical.TScrollbar")
        text.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        text.pack(fill="both", expand=True)
        text.tag_configure("ok", foreground="#4ade80")
        text.tag_configure("warn", foreground=C_WARN)
        text.tag_configure("danger", foreground=C_DANGER)
        text.tag_configure("info", foreground="#7dd3fc")
        text.tag_configure("plain", foreground="#cbd5e1")
        text.tag_configure("dim", foreground="#5a6b85")
        # ts 必须最后创建：同一字符同时挂「等级 tag + ts tag」时，后创建的 ts 前景色生效
        text.tag_configure("ts", foreground="#c084fc")
        return text

    # —— 底部状态栏 ——
    def _build_statusbar(self):
        bar = tk.Frame(self.root, bg=C_BAR)
        bar.pack(fill="x", side="bottom")
        self.status_var = tk.StringVar(value="未启动任何服务 · 点「一键启动」或单个卡片「启动」")
        tk.Label(bar, textvariable=self.status_var, bg=C_BAR, fg=C_MUT,
                 font=(FONT, 9), anchor="w").pack(fill="x", padx=12, pady=4)

    # —— 动作 ——
    def start_one(self, svc: Service):
        threading.Thread(target=self._start_service, args=(svc,), daemon=True).start()

    def stop_one(self, svc: Service):
        threading.Thread(target=self._stop_service, args=(svc,), daemon=True).start()

    def restart_one(self, svc: Service):
        def run():
            self._stop_service(svc)
            time.sleep(0.8)  # 等端口释放
            self._start_service(svc)
        threading.Thread(target=run, daemon=True).start()

    def _start_service(self, svc: Service):
        svc.start(extra_env={"AGENT_COURSE": self.course_var.get()} if svc.key == "agent" else None)

    def _stop_service(self, svc: Service):
        svc.stop()

    def start_all(self):
        """有序启动：matcher/collector/backend 立即起；前端与 Agent 等后端 HTTP 就绪后再起，
        避免「前端早开、后端没起」导致页面连不上后端。"""
        for key in ("matcher", "collector", "backend"):
            svc = next(s for s in SERVICES if s.key == key)
            self.start_one(svc)
        threading.Thread(target=self._start_rest_after_backend, daemon=True).start()
        if self.auto_open.get():
            threading.Thread(target=self._auto_open_when_ready, daemon=True).start()

    def _start_rest_after_backend(self):
        for _ in range(240):  # 最多等 2 分钟后端就绪
            if backend_ready():
                break
            time.sleep(0.5)
        for key in ("frontend", "agent"):
            svc = next(s for s in SERVICES if s.key == key)
            self.start_one(svc)

    def stop_all(self):
        for svc in SERVICES:
            if svc.alive() or svc.status not in ("未启动", "已停止"):
                self.stop_one(svc)
        # 清扫端口残留（上次强杀启动器留下的孤儿进程等）
        threading.Thread(target=self._sweep_orphans, daemon=True).start()

    def _sweep_orphans(self):
        time.sleep(1.5)  # 等停止动作先落地
        for svc in SERVICES:
            if svc.port and port_open(svc.port):
                pid = port_pid(svc.port)
                if pid and looks_ours(cmdline_of(pid)):
                    kill_tree(pid)
                    svc.log("warn", f"已清除端口 {svc.port} 上的平台残留进程（PID {pid}）")

    def open_ui(self):
        frontend = next(s for s in SERVICES if s.key == "frontend")
        webbrowser.open(f"http://localhost:{frontend.port or 5173}")

    def _auto_open_when_ready(self):
        # 后端与前端都真正可用才打开浏览器，避免打开后页面连不上后端
        frontend = next(s for s in SERVICES if s.key == "frontend")
        port = frontend.port or 5173
        for _ in range(360):
            if backend_ready() and port_open(port):
                if not self._opened_browser:
                    self._opened_browser = True
                    webbrowser.open(f"http://localhost:{port}")
                return
            time.sleep(0.5)

    # —— 周期任务 ——
    # 子进程原始日志行的时间戳前缀，例如：
    #   Spring: 2026-10-16T19:22:27.061+08:00  INFO ...
    #   uvicorn/agent: 2026-10-16 19:22:27,061 ...
    #   「全部」页签行首还有 [服务名] 前缀
    TS_RE = re.compile(
        r"^\s*(?:\[[^\]]*\]\s*)?\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:[.,]\d{1,9})?"
        r"(?:\s?(?:[+-]\d{2}:?\d{2}|Z))?"
    )

    # 等级识别两轮：先看日志级别词（只认大写——Spring/uvicorn/logging 输出都是大写，
    # 避免普通英文句里的 error/warning 小写词误伤），没有级别词的行再按异常类名识别。
    LEVEL_RE = [
        ("danger", re.compile(r"\b(?:ERROR|CRITICAL|FATAL)\b")),
        ("warn", re.compile(r"\bWARN(?:ING)?\b")),
        ("dim", re.compile(r"\bDEBUG\b")),
        ("info", re.compile(r"\bINFO\b")),
    ]
    # Traceback/异常类名（ValueError、SocketException 等驼峰类名）
    STACKTRACE_RE = re.compile(r"Traceback|\w*Exception\b|[A-Z]\w*Error\b")

    def _pick_tag(self, text: str, tag: str) -> str:
        """显式等级（ok/warn/danger/info）直接用；子进程原始行（plain）按内容猜等级。"""
        if tag not in ("plain", None):
            return tag
        for name, pattern in self.LEVEL_RE:
            if pattern.search(text):
                return name
        if self.STACKTRACE_RE.search(text):
            return "danger"
        return "plain"

    def _drain_logs(self):
        while True:
            try:
                key, level, text = queue_log.get_nowait()
            except queue.Empty:
                break
            svc = next((s for s in SERVICES if s.key == key), None)
            if svc is None:
                continue
            tag = level or "plain"
            short = svc.title.split(" ·")[0]
            self._append(self.all_text, f"[{short}] {text}\n", tag)
            if svc.widget is not None:
                self._append(svc.widget, text + "\n", tag)
        self.root.after(150, self._drain_logs)

    def _append(self, widget: tk.Text, text: str, tag: str):
        # 智能滚动：只有本来就在底部才跟随，翻历史日志时不会被强拉到底
        at_bottom = widget.yview()[1] > 0.995
        tag = self._pick_tag(text, tag)
        widget.configure(state="normal")
        m = self.TS_RE.match(text)
        if m:
            widget.insert("end", text[: m.end()], ("ts", tag))  # 时间戳单独配色
            widget.insert("end", text[m.end():], tag)
        else:
            widget.insert("end", text, tag)
        if int(widget.index("end-1c").split(".")[0]) > 1500:
            widget.delete("1.0", "500.0")  # 限行防卡
        widget.configure(state="disabled")
        if at_bottom:
            widget.see("end")

    def _refresh(self):
        for svc in SERVICES:
            if svc.alive():
                if svc.status in ("未启动", "已停止") or svc.status.startswith("已退出"):
                    svc.status = "运行中"
            elif svc.status == "运行中":
                svc.status = "已退出"
            self.cards[svc.key].refresh()
        running = sum(1 for s in SERVICES if s.alive())
        frontend = next(s for s in SERVICES if s.key == "frontend")
        url = f"http://localhost:{frontend.port or 5173}"
        if running == 0:
            self.status_var.set("未启动任何服务 · 点「一键启动」或单个卡片「启动」")
        else:
            self.status_var.set(f"运行 {running}/{len(SERVICES)} · 做题台 {url} · 数据保存在 backend\\data\\")
        self.root.after(1000, self._refresh)

    # —— 关闭即全部停止 ——
    def _wire_close(self):
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    def _on_close(self):
        if any(s.alive() for s in SERVICES):
            if not messagebox.askyesno(
                "退出", "关闭窗口将停止全部服务（数据已保存在 backend\\data\\）。确定退出？"
            ):
                return
        for svc in SERVICES:
            svc.stop(announce=False)
        self.root.destroy()


def main():
    try:
        root = tk.Tk()
    except tk.TclError as exc:
        print(f"无法创建窗口：{exc}")
        sys.exit(1)
    try:
        app = App(root)
    except Exception as exc:
        messagebox.showerror("雨课堂启动器", str(exc))
        sys.exit(1)
    for svc in SERVICES:
        svc.log(None, f"项目根目录：{ROOT}")
    root.mainloop()


if __name__ == "__main__":
    main()
