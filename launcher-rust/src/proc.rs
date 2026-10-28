//! 系统工具层：端口探测、进程定位/清理、Java 定位、后端 HTTP 就绪检测。
//!
//! 与 Python 版启动器行为一一对应：
//! - `netstat -ano` 解析端口占用 PID（中文 Windows 输出为 GBK，但端口/LISTENING 均为 ASCII，lossy 解码即可）
//! - PowerShell 查进程命令行（显式让 PowerShell 输出 UTF-8，避免中文乱码）
//! - `taskkill /T /F` 杀进程树
//! - 端口 bind 探测识别 Hyper-V/WSL 保留端口（bind EACCES）

use std::net::{IpAddr, SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;

/// Windows：不闪黑窗
#[cfg(windows)]
pub const CREATE_NO_WINDOW: u32 = 0x0800_0000;
#[cfg(not(windows))]
pub const CREATE_NO_WINDOW: u32 = 0;

/// 给子进程命令挂上「不弹窗」标记（Windows CREATE_NO_WINDOW）。
pub fn set_no_window(cmd: &mut Command) -> &mut Command {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    #[cfg(not(windows))]
    let _ = cmd;
    cmd
}

fn local_addr(port: u16) -> SocketAddr {
    SocketAddr::new(IpAddr::from([127, 0, 0, 1]), port)
}

/// 判断端口是否已有服务在监听（TCP 连得上即算）。
pub fn port_open(port: u16) -> bool {
    TcpStream::connect_timeout(&local_addr(port), Duration::from_millis(300)).is_ok()
}

/// 绑定探测（供端口解析细分失败原因）：保留区间 → PermissionDenied（立即），
/// 已被监听 → AddrInUse（立即），可绑 → Ok。不要用 connect 探测保留区间——
/// SYN 会被静默丢弃，每个端口都要等满超时。
pub fn try_bind(port: u16) -> std::io::Result<TcpListener> {
    TcpListener::bind(local_addr(port))
}

/// 解析 `netstat -ano -p tcp` 输出，返回正在 LISTEN 指定端口的 PID（无则 None）。
pub fn port_pid(port: u16) -> Option<u32> {
    let mut cmd = Command::new("netstat");
    let out = set_no_window(&mut cmd)
        .args(["-ano", "-p", "tcp"])
        .output()
        .ok()?;
    let text = String::from_utf8_lossy(&out.stdout);
    let suffix = format!(":{port}");
    for line in text.lines() {
        let tokens: Vec<&str> = line.split_whitespace().collect();
        // TCP  本地地址  远程地址  状态  PID
        if tokens.len() == 5 && tokens[0].eq_ignore_ascii_case("TCP") {
            if tokens[1].ends_with(&suffix) && tokens[3].eq_ignore_ascii_case("LISTENING") {
                return tokens[4].parse::<u32>().ok();
            }
        }
    }
    None
}

/// 读进程命令行（PowerShell 显式 UTF-8 输出，防中文乱码）；失败返回空串。
pub fn cmdline_of(pid: u32) -> String {
    let script = format!(
        "[Console]::OutputEncoding=[Text.Encoding]::UTF8; \
         (Get-CimInstance Win32_Process -Filter 'ProcessId={pid}').CommandLine"
    );
    let mut cmd = Command::new("powershell");
    set_no_window(&mut cmd)
        .args(["-NoProfile", "-Command", &script])
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .unwrap_or_default()
}

/// 平台进程的命令行特征：只清理「确定属于本平台」的残留，外来程序一律不动。
const OURS_MARKERS: [&str; 6] = ["ykt-platform", "vite.js", "app:app", "main.py", "雨课堂启动器", "做题"];

pub fn looks_ours(cmd: &str) -> bool {
    OURS_MARKERS.iter().any(|m| cmd.contains(m))
}

/// 强制结束进程树（子进程一并干掉：npm→node、java→mvn 链等）。
pub fn kill_tree(pid: u32) {
    let mut cmd = Command::new("taskkill");
    let _ = set_no_window(&mut cmd)
        .args(["/PID", &pid.to_string(), "/T", "/F"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

/// 在 PATH 中查找可执行文件（仅 .exe；mvn/npm 这类 .cmd 需经 cmd /C 调用）。
pub fn which(name: &str) -> Option<PathBuf> {
    let exe = if name.to_ascii_lowercase().ends_with(".exe") {
        name.to_string()
    } else {
        format!("{name}.exe")
    };
    let path = std::env::var("PATH").unwrap_or_default();
    for dir in path.split(';') {
        if dir.is_empty() {
            continue;
        }
        let cand = Path::new(dir).join(&exe);
        if cand.is_file() {
            return Some(cand);
        }
    }
    None
}

/// 后端 Spring 是否已可响应 HTTP：对 /api/auth/login 发一个 POST，
/// 能读到任意响应（含 400/401）即算就绪——该接口只收 POST，不留 GET-not-supported 的 WARN。
pub fn backend_ready(port: u16) -> bool {
    use std::io::{Read, Write};
    let Ok(mut stream) = TcpStream::connect_timeout(&local_addr(port), Duration::from_secs(1))
    else {
        return false;
    };
    let body = "{}";
    let req = format!(
        "POST /api/auth/login HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n\
         Content-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        body.len(),
        body
    );
    if stream.write_all(req.as_bytes()).is_err() {
        return false;
    }
    let mut buf = [0u8; 1];
    stream.read_exact(&mut buf).is_ok()
}

/// 读 java 主版本号（`java -version` 输出在 stderr，形如 openjdk version "21.0.4"）。
pub fn java_major(java_exe: &Path) -> u32 {
    let mut cmd = Command::new(java_exe);
    let Ok(out) = set_no_window(&mut cmd).arg("-version").output() else {
        return 0;
    };
    let text = String::from_utf8_lossy(&out.stderr);
    for part in text.replace('"', " ").split_whitespace() {
        if part.chars().next().is_some_and(|c| c.is_ascii_digit()) {
            return part
                .split('.')
                .next()
                .and_then(|s| s.parse().ok())
                .unwrap_or(0);
        }
    }
    0
}

/// 后端按 Java 21 编译：在候选里实际测版本，选第一个 21+ 的 java；都没有再退回 PATH 上的。
pub fn resolve_java() -> PathBuf {
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Ok(home) = std::env::var("JAVA_HOME") {
        candidates.push(Path::new(&home).join("bin").join("java.exe"));
    }
    candidates.push(PathBuf::from(r"C:\Program Files\Zulu\zulu-21\bin\java.exe"));
    candidates.push(PathBuf::from(r"C:\Program Files\Java\jdk-21\bin\java.exe"));
    if let Some(p) = which("java") {
        candidates.push(p);
    }

    let mut seen: Vec<PathBuf> = Vec::new();
    for cand in candidates {
        if cand.is_file() && !seen.contains(&cand) {
            seen.push(cand);
        }
    }
    for cand in &seen {
        if java_major(cand) >= 21 {
            return cand.clone();
        }
    }
    seen.into_iter().next().unwrap_or_else(|| PathBuf::from("java"))
}

/// 从 exe（打包后）或本程序（源码运行）向上找同时含 backend 与 agent 子目录的项目根。
pub fn find_root() -> Option<PathBuf> {
    let mut starts: Vec<PathBuf> = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            starts.push(dir.to_path_buf());
        }
    }
    if let Ok(cwd) = std::env::current_dir() {
        starts.push(cwd);
    }
    for start in starts {
        let mut dir = Some(start);
        while let Some(d) = dir {
            if d.join("backend").is_dir() && d.join("agent").is_dir() {
                return Some(d);
            }
            dir = d.parent().map(|p| p.to_path_buf());
        }
    }
    None
}

/// 用系统默认浏览器打开 URL。
pub fn open_browser(url: &str) {
    let mut cmd = Command::new("cmd");
    let _ = set_no_window(&mut cmd).args(["/C", "start", "", url]).spawn();
}
