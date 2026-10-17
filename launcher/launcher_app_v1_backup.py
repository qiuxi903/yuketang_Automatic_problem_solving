# -*- coding: utf-8 -*-
"""
雨课堂自动化做题平台 · 单窗口启动器

一个 exe 管全部服务：matcher / collector / backend / frontend / agent。
- 不弹服务窗口：所有子进程隐藏运行，输出汇总到本窗口各标签页
- 双击即用：自动定位项目根目录；后端 jar / 前端依赖 / Agent 浏览器缺失时先自动准备再启动
- 关闭窗口即停止全部服务

打包：python -m PyInstaller --noconfirm --onefile --noconsole --name 雨课堂启动器 launcher_app.py
"""

import os
import queue
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
    """后端 Spring 是否已可响应 HTTP（能拿到任意响应即算就绪，不要求 200）。"""
    try:
        conn = HTTPConnection("127.0.0.1", 8080, timeout=1)
        conn.request("GET", "/api/auth/login")
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
    def __init__(self, key, title, port, builder, log_tag_colors=None):
        self.key = key
        self.title = title
        self.port = port          # agent 无端口，置 None
        self.builder = builder    # 返回 (argv, cwd, env)；准备型服务先跑 prepare
        self.proc = None
        self.status = "未启动"
        self.q = None
        self.widget = None        # 日志 Text
        self.notebook_frame = None

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
            # builder 内部已把失败原因写进日志（如浏览器下载失败），这里不再重复启动
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
        (self.q or queue_log).put((self.key, level, text))


queue_log = queue.Queue()


# —— 各服务的启动参数（准备缺失产物后给出直接子进程命令，避免嵌套 shell） ——

def build_matcher():
    return (
        [resolve_python(), "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8100"],
        ROOT / "matcher",
        None,
    )


def build_collector():
    return (
        [resolve_python(), "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8200"],
        ROOT / "collector",
        None,
    )


def _ensure_backend_jar(svc: "Service"):
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


def build_backend(svc: "Service"):
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


def resolve_frontend_port(svc: "Service"):
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


def build_frontend(svc: "Service"):
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


def chromium_installed() -> bool:
    """playwright 自带浏览器缓存里是否有 chromium（现 Agent 走系统 Chrome 通道，仅作检测用）。"""
    cache = Path(os.environ.get("LOCALAPPDATA", "")) / "ms-playwright"
    return cache.is_dir() and any(cache.glob("chromium*"))


def build_agent(svc: "Service"):
    # Agent 通过 playwright 的 channel 机制直接使用系统浏览器（Chrome/Edge，见 agent\config.py
    # 的 BROWSER_CHANNEL，默认 chrome），无需下载 playwright 自带的 chromium。
    return ([resolve_python(), "main.py"], ROOT / "agent", None)


SERVICES = [
    Service("matcher", "Matcher · 匹配服务", 8100, lambda svc: build_matcher()),
    Service("collector", "Collector · 采集服务", 8200, lambda svc: build_collector()),
    Service("backend", "Backend · 后端 (Spring Boot)", 8080, lambda svc: build_backend(svc)),
    Service("frontend", "Frontend · 前端 (Vite)", 5173, lambda svc: build_frontend(svc)),
    Service("agent", "Agent · 做题执行器", None, lambda svc: build_agent(svc)),
]


# ---------------------------------------------------------------- 界面

STATUS_COLOR = {
    "运行中": "#16a34a",
    "已启动": "#16a34a",
    "构建中": "#d97706",
    "安装依赖中": "#d97706",
    "准备浏览器中": "#d97706",
    "端口被占": "#ea580c",
    "构建失败": "#dc2626",
    "安装失败": "#dc2626",
    "准备失败": "#dc2626",
    "已停止": "#9ca3af",
    "未启动": "#9ca3af",
}


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        root.title("雨课堂启动器 · 雨课堂自动化做题平台")
        root.geometry("980x660")
        root.minsize(860, 540)

        self.course_var = tk.StringVar(value=os.environ.get("AGENT_COURSE") or "高二理论")
        # 自动打开浏览器默认关闭：用户手动点「打开做题台」；若勾选，也会等后端+前端都就绪才打开
        self.auto_open = tk.BooleanVar(value=False)
        self._opened_browser = False

        self._build_toolbar()
        self._build_body()
        self._wire_close()

        self.root.after(200, self._drain_logs)
        self.root.after(1000, self._refresh_status)

        # 手动启动：不自动开服务、不自动开浏览器，由用户点「一键启动」
        # 原因：开机后立即自动拉起容易出现后端尚未就绪、前端连不上的情况

    # —— 顶部工具栏 ——
    def _build_toolbar(self):
        bar = tk.Frame(self.root, bg="#111827")
        bar.pack(fill="x")
        tk.Label(
            bar, text="雨课堂启动器", bg="#111827", fg="#ffffff",
            font=("Microsoft YaHei UI", 13, "bold"),
        ).pack(side="left", padx=16, pady=10)

        def btn(text, cmd, primary=False):
            tk.Button(
                bar, text=text, command=cmd, cursor="hand2",
                bg="#16a34a" if primary else "#374151",
                fg="#ffffff", activebackground="#15803d" if primary else "#4b5563",
                activeforeground="#ffffff", relief="flat", padx=14, pady=4,
                font=("Microsoft YaHei UI", 10),
            ).pack(side="left", padx=4, pady=8)

        btn("一键启动", self.start_all, primary=True)
        btn("全部停止", self.stop_all)
        btn("打开做题台", self.open_ui)
        tk.Checkbutton(
            bar, text="服务就绪后自动打开", variable=self.auto_open,
            bg="#111827", fg="#d1d5db", selectcolor="#111827",
            activebackground="#111827", activeforeground="#ffffff",
        ).pack(side="left", padx=8)

        course = tk.Frame(bar, bg="#111827")
        course.pack(side="right", padx=12)
        tk.Label(course, text="课程名：", bg="#111827", fg="#d1d5db").pack(side="left")
        tk.Entry(course, textvariable=self.course_var, width=12).pack(side="left")
        tk.Label(course, text="（重启 Agent 生效）", bg="#111827", fg="#6b7280",
                 font=("Microsoft YaHei UI", 9)).pack(side="left")

    # —— 中部：总览 + 日志页签 ——
    def _build_body(self):
        body = tk.Frame(self.root)
        body.pack(fill="both", expand=True)

        overview = tk.Frame(body)
        self.notebook = ttk.Notebook(body)
        self.notebook.add(overview, text="总览")
        self.notebook.pack(fill="both", expand=True, padx=8, pady=8)

        cols = ("service", "port", "status", "pid")
        self.tree = ttk.Treeview(overview, columns=cols, show="headings", height=5)
        for cid, text, width in [
            ("service", "服务", 260), ("port", "端口", 80),
            ("status", "状态", 160), ("pid", "PID", 90),
        ]:
            self.tree.heading(cid, text=text)
            self.tree.column(cid, width=width, anchor="w")
        self.tree.pack(fill="x", padx=10, pady=(10, 6))
        self.tree.tag_configure("odd", background="#f3f4f6")

        row = tk.Frame(overview)
        row.pack(fill="x", padx=10, pady=(0, 8))
        ttk.Button(row, text="启动选中", command=lambda: self.act_selected("start")).pack(side="left", padx=3)
        ttk.Button(row, text="停止选中", command=lambda: self.act_selected("stop")).pack(side="left", padx=3)
        ttk.Button(row, text="重启选中", command=lambda: self.act_selected("restart")).pack(side="left", padx=3)
        tk.Label(
            overview,
            text="提示：关闭本窗口会停止全部服务；后端数据保存在 backend\\data\\，重启不丢。",
            fg="#6b7280", anchor="w",
        ).pack(fill="x", padx=12, pady=(0, 10))

        for svc in SERVICES:
            frame = tk.Frame(self.notebook)
            self.notebook.add(frame, text=svc.title.split(" ·")[0])
            text = tk.Text(
                frame, wrap="none", state="disabled", bg="#0b1020", fg="#cbd5e1",
                insertbackground="#cbd5e1", font=("Consolas", 10), relief="flat",
            )
            scroll = ttk.Scrollbar(frame, command=text.yview)
            text.configure(yscrollcommand=scroll.set)
            scroll.pack(side="right", fill="y")
            text.pack(fill="both", expand=True)
            text.tag_configure("ok", foreground="#4ade80")
            text.tag_configure("warn", foreground="#fbbf24")
            text.tag_configure("danger", foreground="#f87171")
            text.tag_configure("info", foreground="#94a3b8")
            text.tag_configure("plain", foreground="#cbd5e1")
            svc.widget = text
            svc.notebook_frame = frame

        self._reload_tree()

    def _reload_tree(self):
        for iid in self.tree.get_children():
            self.tree.delete(iid)
        for i, svc in enumerate(SERVICES):
            self.tree.insert(
                "", "end", iid=svc.key,
                values=(svc.title, svc.port or "—", svc.status, svc.proc.pid if svc.proc else "—"),
                tags=("odd",) if i % 2 else (),
            )

    # —— 动作 ——
    def start_all(self):
        """有序启动：matcher/collector/backend 立即起；前端与 Agent 等后端 HTTP 就绪后再起，
        避免「前端早开、后端没起」导致页面连不上后端。"""
        for key in ("matcher", "collector", "backend"):
            svc = next(s for s in SERVICES if s.key == key)
            threading.Thread(target=self._start_service, args=(svc,), daemon=True).start()
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
            threading.Thread(target=self._start_service, args=(svc,), daemon=True).start()

    def _start_service(self, svc: Service):
        svc.start(extra_env={"AGENT_COURSE": self.course_var.get()} if svc.key == "agent" else None)
        self.root.after(0, self._reload_tree)

    def stop_all(self):
        for svc in SERVICES:
            if svc.alive() or svc.status not in ("未启动", "已停止"):
                svc.stop()
        # 清扫端口残留（上次强杀启动器留下的孤儿进程等）
        for svc in SERVICES:
            if svc.port and port_open(svc.port):
                pid = port_pid(svc.port)
                if pid and looks_ours(cmdline_of(pid)):
                    kill_tree(pid)
                    svc.log("warn", f"已清除端口 {svc.port} 上的平台残留进程（PID {pid}）")
        self._reload_tree()

    def act_selected(self, action):
        sel = self.tree.selection()
        if not sel:
            return
        svc = next(s for s in SERVICES if s.key == sel[0])
        if action == "start":
            threading.Thread(target=self._start_service, args=(svc,), daemon=True).start()
        elif action == "stop":
            svc.stop()
            self._reload_tree()
        elif action == "restart":
            svc.stop()
            time.sleep(0.6)
            threading.Thread(target=self._start_service, args=(svc,), daemon=True).start()

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
    def _drain_logs(self):
        while True:
            try:
                key, level, text = queue_log.get_nowait()
            except Exception:
                break
            svc = next(s for s in SERVICES if s.key == key)
            if svc.widget is None:
                continue
            tag = level or "plain"
            svc.widget.configure(state="normal")
            svc.widget.insert("end", text + "\n", tag)
            if int(svc.widget.index("end-1c").split(".")[0]) > 4000:
                svc.widget.delete("1.0", "1000.0")
            svc.widget.configure(state="disabled")
            svc.widget.see("end")
        self.root.after(200, self._drain_logs)

    def _refresh_status(self):
        for svc in SERVICES:
            if svc.alive():
                if svc.status in ("未启动", "已停止") or svc.status.startswith("已退出"):
                    svc.status = "运行中"
            elif svc.status == "运行中":
                svc.status = "已退出"
        self._reload_tree()
        self.root.after(1000, self._refresh_status)

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
    except RuntimeError as exc:
        messagebox.showerror("雨课堂启动器", str(exc))
        sys.exit(1)
    for svc in SERVICES:
        svc.log(None, f"项目根目录：{ROOT}")
    root.mainloop()


if __name__ == "__main__":
    main()
