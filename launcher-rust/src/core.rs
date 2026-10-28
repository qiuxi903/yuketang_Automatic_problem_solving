//! 服务控制层：5 个服务的定义、启动参数构建、进程生命周期、一键启动编排。
//!
//! 状态只存于 Core（互斥锁保护）；日志与退出事件经 channel 送到 UI 线程。
//!
//! 端口策略（关键）：Windows 的 Hyper-V/WSL 会保留大段动态端口（netsh excludedportrange，
//! 且每次重启后漂移），落在区间里的端口 bind 报 10013（EACCES），谁也绑不上——
//! 8080/8100/8200 都可能中招。因此所有服务启动前统一做端口解析：
//! 首选基准端口（上次解析结果优先，便于重启复用），不可用则向后扫描，找到
//! 「bind 得上 + 没监听者 + 未被本会话其它服务预留」的端口，并联动修正
//! agent 的服务地址与 vite 代理，保证整条链路仍然互通。

use std::collections::HashSet;
use std::env;
use std::fs;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{mpsc, Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use crate::logs::Level;
use crate::proc::*;

// ---------------------------------------------------------------- 消息与服务定义

#[derive(Debug)]
pub enum Msg {
    /// 子进程/启动器的一行日志。level=None 表示原始行，UI 端按内容猜等级。
    Log { key: usize, level: Option<Level>, text: String },
    /// 进程退出（读者线程在 stdout EOF 后上报）。
    Exited { key: usize, code: Option<i32> },
}

#[allow(dead_code)]
pub struct ServiceDef {
    pub key: &'static str,
    pub short: &'static str,
    pub title: &'static str,
    /// 基准端口；agent 无端口。
    pub base_port: Option<u16>,
    /// 端口不可用时向后扫描的范围宽度。
    pub port_span: u16,
}

pub const SERVICES: [ServiceDef; 5] = [
    ServiceDef { key: "matcher",   short: "Matcher",   title: "Matcher · 匹配服务",           base_port: Some(28100), port_span: 800 },
    ServiceDef { key: "collector", short: "Collector", title: "Collector · 采集服务",         base_port: Some(28200), port_span: 800 },
    ServiceDef { key: "backend",   short: "Backend",   title: "Backend · 后端 (Spring Boot)", base_port: Some(28080), port_span: 800 },
    ServiceDef { key: "frontend",  short: "Frontend",  title: "Frontend · 前端 (Vite)",       base_port: Some(25173), port_span: 20 },
    ServiceDef { key: "agent",     short: "Agent",     title: "Agent · 做题执行器",           base_port: None,        port_span: 0 },
];

#[derive(Clone)]
pub struct ServiceState {
    pub port: Option<u16>,
    pub pid: Option<u32>,
    pub status: String,
    pub running: bool,
}

pub struct Core {
    pub root: PathBuf,
    /// 与 SERVICES 同序。
    pub states: Vec<ServiceState>,
    pub course: String,
    /// 正在进行的启动流程（防重复点击）。
    pub starting: [bool; 5],
    /// 本会话已分配给各服务的端口（防止并发解析时撞车）。
    pub reserved: HashSet<u16>,
    pub tx: mpsc::Sender<Msg>,
}

impl Core {
    fn log(&self, key: usize, level: Option<Level>, text: impl Into<String>) {
        let _ = self.tx.send(Msg::Log { key, level, text: text.into() });
    }

    fn set_status(&mut self, key: usize, status: &str) {
        self.states[key].status = status.to_string();
    }
}

// ---------------------------------------------------------------- 端口解析

/// 为服务解析可用端口（不得在持有 Core 锁时调用——free_port 内部会加锁）。
///
/// 候选顺序：上次解析结果（重启复用）→ 基准端口 → 向后扫描 port_span。
/// 每个候选：有监听者 → 结束占用者（平台残留或外来程序都腾位，见 free_port）；
/// bind 不上且无监听者 = 系统保留区间（Hyper-V 排除段，杀无可杀）→ 换下一个；
/// 通过且未预留 → 选中。
fn resolve_service_port(core: &Arc<Mutex<Core>>, key: usize) -> Option<u16> {
    let (base, span) = {
        let c = core.lock().unwrap();
        (c.states[key].port.or(SERVICES[key].base_port), SERVICES[key].port_span)
    };
    let base = base?;

    let mut candidates: Vec<u16> = Vec::new();
    if base > SERVICES[key].base_port.unwrap_or(base) {
        candidates.push(base); // 上次换过的端口优先复用
    }
    for p in base..=base.saturating_add(span) {
        candidates.push(p);
    }

    let mut blocked = false; // 基准端口是否因系统保留/占用无法归位（用于换口提示）
    for port in candidates {
        // bind 一步分流（全部瞬时完成）：保留区间 → PermissionDenied；
        // 有监听者 → AddrInUse（走杀进程腾位）；空闲 → Ok
        let probe = try_bind(port);
        let occupied = matches!(&probe, Err(e) if e.kind() == std::io::ErrorKind::AddrInUse);
        if matches!(&probe, Err(_)) && !occupied {
            blocked = true;
            continue; // 系统保留区间等，杀无可杀 → 换下一个
        }
        drop(probe);
        if occupied {
            // 本会话其它服务已预留的端口绝不互杀，直接换下一个
            if core.lock().unwrap().reserved.contains(&port) {
                blocked = true;
                continue;
            }
            blocked = true;
            if !free_port(core, key, port) {
                continue; // 占用者结束不掉（系统进程等） → 换下一个
            }
            if try_bind(port).is_err() {
                continue; // 清了仍绑不上
            }
        }
        let mut c = core.lock().unwrap();
        if c.reserved.contains(&port) {
            continue; // 本会话其它服务刚预留
        }
        if port != SERVICES[key].base_port.unwrap_or(port) {
            let reason = if blocked { "被系统保留区间吞掉或占用者无法结束" } else { "不可用" };
            c.log(key, Some(Level::Warn),
                format!("端口 {} {}，{} 改用 {}（保留区间随重启漂移，届时会自动回到标准端口）",
                        SERVICES[key].base_port.unwrap_or(port), reason, SERVICES[key].short, port));
        }
        c.reserved.insert(port);
        return Some(port);
    }
    None
}

/// 系统关键进程名单：占用者命中时绝不结束，换端口处理（防止误杀系统组件）。
const PROTECTED_PROCESSES: [&str; 15] = [
    "system", "idle", "registry", "memory compression", "svchost.exe", "smss.exe", "csrss.exe",
    "wininit.exe", "winlogon.exe", "services.exe", "lsass.exe", "dwm.exe", "explorer.exe",
    "fontdrvhost.exe", "msmpeng.exe",
];

/// 腾空端口：有监听者就结束它。平台残留进程直接清理；外来程序按策略结束
/// （写明 PID 与进程，便于用户追责）；系统关键进程 / 命令行不可读者一律不杀。
/// 返回 true = 端口已可用。
fn free_port(core: &Arc<Mutex<Core>>, key: usize, port: u16) -> bool {
    let Some(pid) = port_pid(port) else { return true }; // 无监听者
    if pid == std::process::id() {
        return false; // 防御：绝不结束启动器自身
    }
    let cmd = cmdline_of(pid);
    // 读不到命令行（权限不足，多半是系统进程）→ 不杀
    if cmd.is_empty() {
        core.lock().unwrap().log(key, Some(Level::Warn),
            format!("端口 {port} 的占用者（PID {pid}）无法读取命令行，可能是系统进程，不结束它"));
        return false;
    }
    let exe = cmd.split_whitespace().next().unwrap_or("")
        .rsplit(['\\', '/']).next().unwrap_or("").to_ascii_lowercase();
    if PROTECTED_PROCESSES.contains(&exe.as_str()) {
        core.lock().unwrap().log(key, Some(Level::Warn),
            format!("端口 {port} 的占用者是系统进程（{exe}，PID {pid}），不结束它"));
        return false;
    }
    let ours = looks_ours(&cmd);
    let desc = cmd.chars().take(70).collect::<String>();
    {
        let c = core.lock().unwrap();
        if ours {
            c.log(key, Some(Level::Warn),
                format!("端口 {port} 被平台残留进程占用（PID {pid}：{desc}），自动清除…"));
        } else {
            c.log(key, Some(Level::Warn),
                format!("端口 {port} 被其它程序占用（PID {pid}：{desc}），结束它以腾出端口…"));
        }
    }
    kill_tree(pid);
    thread::sleep(Duration::from_millis(900));
    if port_open(port) {
        core.lock().unwrap().log(key, Some(Level::Danger),
            format!("端口 {port} 上的进程（PID {pid}）未能结束，换用其它端口"));
        return false;
    }
    core.lock().unwrap().log(key, Some(Level::Ok),
        format!("已结束 PID {pid}，端口 {port} 已腾出"));
    true
}

// ---------------------------------------------------------------- 启动流程

/// 单个服务的待启动命令。
struct SpawnPlan {
    argv: Vec<String>,
    cwd: PathBuf,
    env: Vec<(String, String)>,
}

/// 启动某个服务（后台线程执行；构建类准备可能耗时）。
pub fn start_service(core: &Arc<Mutex<Core>>, key: usize) {
    let (tx, root) = {
        let mut c = core.lock().unwrap();
        if c.starting[key] || c.states[key].running {
            c.log(key, Some(Level::Warn), "已在运行，无需重复启动");
            return;
        }
        c.starting[key] = true;
        (c.tx.clone(), c.root.clone())
    };
    let core2 = Arc::clone(core);
    thread::spawn(move || {
        let result = run_start(&core2, key, &tx, &root);
        core2.lock().unwrap().starting[key] = false;
        if let Err(text) = result {
            let mut c = core2.lock().unwrap();
            c.set_status(key, "启动失败");
            c.log(key, Some(Level::Danger), text);
        }
    });
}

fn run_start(core: &Arc<Mutex<Core>>, key: usize, tx: &mpsc::Sender<Msg>, root: &Path) -> Result<(), String> {
    // 1) 端口解析（可能清残留/向后扫描；agent 无端口直接跳过）
    if SERVICES[key].base_port.is_some() {
        let chosen = resolve_service_port(core, key);
        let mut c = core.lock().unwrap();
        match chosen {
            Some(port) => c.states[key].port = Some(port),
            None => {
                let base = SERVICES[key].base_port.unwrap_or(0);
                c.set_status(key, "启动失败");
                c.log(key, Some(Level::Danger),
                    format!("{base}-{} 端口均不可用（被系统保留或其它程序占用），无法启动",
                            base + SERVICES[key].port_span));
                return Ok(());
            }
        }
    }

    // 2) 构建启动命令（mvn/npm 等耗时准备在这里发生；None = 已接管/无需启动）
    let Some(plan) = build_plan(core, key, tx, root)? else {
        return Ok(());
    };

    // 3) 拉起子进程（隐藏窗口，stdout/stderr 双管道由读者线程汇聚）
    let mut cmd = Command::new(&plan.argv[0]);
    cmd.args(&plan.argv[1..])
        .current_dir(&plan.cwd)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    set_no_window(&mut cmd);
    let mut env = plan.env.clone();
    env.push(("PYTHONUNBUFFERED".to_string(), "1".to_string()));
    // 中文 Windows 上 Python 子进程默认按 GBK 写管道，强制 UTF-8 才不乱码
    env.push(("PYTHONIOENCODING".to_string(), "utf-8".to_string()));
    for (k, v) in &env {
        cmd.env(k, v);
    }
    let mut child: Child = cmd.spawn().map_err(|e| format!("启动失败：{e}"))?;
    let pid = child.id();

    {
        let mut c = core.lock().unwrap();
        c.states[key].pid = Some(pid);
        c.states[key].running = true;
        c.states[key].status = "运行中".to_string();
        c.log(key, Some(Level::Ok), format!("已启动（PID {pid}）"));
    }

    // 4) 读者线程：stdout/stderr 各自泵到 channel，EOF 后 wait 并上报退出
    let tx2 = tx.clone();
    thread::spawn(move || {
        let stdout = child.stdout.take();
        let stderr = child.stderr.take();
        let mut joins = Vec::new();
        if let Some(stream) = stdout {
            joins.push(spawn_reader(tx2.clone(), key, stream));
        }
        if let Some(stream) = stderr {
            joins.push(spawn_reader(tx2.clone(), key, stream));
        }
        for j in joins {
            let _ = j.join();
        }
        let code = child.wait().ok().and_then(|s| s.code());
        let _ = tx2.send(Msg::Exited { key, code });
    });
    Ok(())
}

/// 单个输出流的日志泵：逐行送到 channel（stdout/stderr 类型不同，泛型收敛）。
/// 命中已知的「看不懂但高频」的报错时，补一条人话提示。
fn spawn_reader<S: std::io::Read + Send + 'static>(
    tx: mpsc::Sender<Msg>,
    key: usize,
    stream: S,
) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        let reader = BufReader::new(stream);
        for line in reader.lines() {
            match line {
                Ok(l) => {
                    let hint = if l.contains("Unable to determine Dialect")
                        || l.contains("Database may be already in use")
                        || l.contains("lockFileChannel")
                    {
                        Some("⚠ 后端连不上数据库：H2 数据库文件同一时刻只允许一个后端实例。\
                              多半是已有另一个后端在运行（或上次异常退出残留），请先停止其它后端再启动")
                    } else {
                        None
                    };
                    let _ = tx.send(Msg::Log { key, level: None, text: l });
                    if let Some(h) = hint {
                        let _ = tx.send(Msg::Log { key, level: Some(Level::Warn), text: h.to_string() });
                    }
                }
                Err(_) => break,
            }
        }
    })
}

/// 清理孤儿 Agent：命令行含 main.py 的 python 进程，且其父进程已退出
/// （典型场景：上次启动器被强制关闭，Agent 没被清理，带着过期端口一直刷警告）。
/// 返回清理数量。
fn kill_orphan_agents(core: &Arc<Mutex<Core>>, key: usize) -> usize {
    let script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; \
        Get-CimInstance Win32_Process -Filter \"Name='python.exe'\" | \
        Where-Object { $_.CommandLine -like '*main.py*' } | \
        ForEach-Object { \
            $parentAlive = $null -ne (Get-Process -Id $_.ParentProcessId -ErrorAction SilentlyContinue); \
            if (-not $parentAlive) { $_.ProcessId } \
        }";
    let mut cmd = Command::new("powershell");
    let Ok(out) = set_no_window(&mut cmd).args(["-NoProfile", "-Command", script]).output() else {
        return 0;
    };
    let text = String::from_utf8_lossy(&out.stdout);
    let pids: Vec<u32> = text.lines().filter_map(|l| l.trim().parse::<u32>().ok()).collect();
    let mut killed = 0;
    for pid in pids {
        if pid == std::process::id() {
            continue;
        }
        kill_tree(pid);
        killed += 1;
    }
    if killed > 0 {
        core.lock().unwrap().log(key, Some(Level::Warn),
            format!("已清理 {killed} 个上次遗留的 Agent 进程（父进程已退出，端口配置可能已过期）"));
    }
    killed
}

/// 查找已在运行的后端（命令行含 ykt-platform 的 java 进程），返回 (pid, 端口)。
/// 后端独占 H2 数据库文件，启动前必须先确认没有第二个实例，否则新实例会因库锁崩溃。
fn find_running_backend() -> Option<(u32, Option<u16>)> {
    let script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; \
        Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | \
        Where-Object { $_.CommandLine -like '*ykt-platform*' } | \
        ForEach-Object { \"$($_.ProcessId)|$($_.CommandLine)\" }";
    let mut cmd = Command::new("powershell");
    let out = set_no_window(&mut cmd)
        .args(["-NoProfile", "-Command", script])
        .output()
        .ok()?;
    let text = String::from_utf8_lossy(&out.stdout);
    for line in text.lines() {
        let line = line.trim();
        let Some((pid_s, cmdline)) = line.split_once('|') else { continue };
        let Ok(pid) = pid_s.trim().parse::<u32>() else { continue };
        if pid == std::process::id() {
            continue;
        }
        let port = cmdline
            .split_whitespace()
            .find_map(|t| t.strip_prefix("--server.port=").and_then(|v| v.parse::<u16>().ok()));
        return Some((pid, port));
    }
    None
}

// ---------------------------------------------------------------- 各服务的命令构建

fn python() -> String {
    which("python").map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_else(|| "python".to_string())
}

fn node() -> String {
    which("node").map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_else(|| "node".to_string())
}

fn build_plan(
    core: &Arc<Mutex<Core>>,
    key: usize,
    _tx: &mpsc::Sender<Msg>,
    root: &Path,
) -> Result<Option<SpawnPlan>, String> {
    match key {
        0 | 1 => {
            let port = core.lock().unwrap().states[key].port.unwrap_or(SERVICES[key].base_port.unwrap());
            Ok(Some(SpawnPlan {
                argv: vec![python(), "-m".into(), "uvicorn".into(), "app:app".into(),
                           "--host".into(), "0.0.0.0".into(), "--port".into(), port.to_string()],
                cwd: root.join(if key == 0 { "matcher" } else { "collector" }),
                env: vec![],
            }))
        }
        2 => build_backend(core, key, root),
        3 => build_frontend(core, key, root).map(Some),
        4 => {
            // 启动前先清掉上次遗留的孤儿 Agent（父进程已退出、端口配置过期）
            kill_orphan_agents(core, key);
            // agent：注入实际服务地址（端口可能已因保留区间而漂移）
            let (course, m, c, b) = {
                let cc = core.lock().unwrap();
                (cc.course.clone(),
                 cc.states[0].port.unwrap_or(28100),
                 cc.states[1].port.unwrap_or(28200),
                 cc.states[2].port.unwrap_or(28080))
            };
            Ok(Some(SpawnPlan {
                argv: vec![python(), "main.py".into()],
                cwd: root.join("agent"),
                env: vec![
                    ("AGENT_COURSE".to_string(), course),
                    ("MATCHER_BASE".to_string(), format!("http://localhost:{m}")),
                    ("COLLECTOR_BASE".to_string(), format!("http://localhost:{c}")),
                    ("BACKEND_BASE".to_string(), format!("http://localhost:{b}")),
                ],
            }))
        }
        _ => unreachable!(),
    }
}

fn find_backend_jar(root: &Path) -> Option<PathBuf> {
    let target = root.join("backend").join("target");
    let mut jars: Vec<PathBuf> = fs::read_dir(target).ok()?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.extension().is_some_and(|e| e == "jar"))
        .filter(|p| !p.to_string_lossy().ends_with(".original"))
        .collect();
    jars.sort();
    jars.into_iter().next()
}

/// 递归收集 dir 下所有文件路径。
fn collect_files(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(entries) = fs::read_dir(dir) else { return };
    for e in entries.flatten() {
        let p = e.path();
        if p.is_dir() {
            collect_files(&p, out);
        } else {
            out.push(p);
        }
    }
}

/// jar 是否过期：源码/资源里任一文件的修改时间比 jar 新 → 过期
/// （典型场景：上次构建时后端还在运行，文件锁导致 jar 没被真正覆盖）。
fn jar_stale(root: &Path, jar: &Path) -> bool {
    let Ok(jar_time) = jar.metadata().and_then(|m| m.modified()) else { return false };
    let mut files = Vec::new();
    collect_files(&root.join("backend").join("src"), &mut files);
    files.iter().filter_map(|p| p.metadata().ok().and_then(|m| m.modified().ok()))
        .any(|t| t > jar_time)
}

/// 同步执行 mvn/npm 等准备命令，把输出尾部写进日志。
fn run_prepare(core: &Arc<Mutex<Core>>, key: usize, argv: &[&str], cwd: &Path,
               env: &[(&'static str, String)], tail_lines: usize) -> Result<(), String> {
    let mut cmd = Command::new(argv[0]);
    cmd.args(&argv[1..]).current_dir(cwd);
    set_no_window(&mut cmd);
    for (k, v) in env {
        cmd.env(k, v);
    }
    let out = cmd.output().map_err(|e| format!("{:?} 执行失败：{e}", argv[0]))?;
    let text = String::from_utf8_lossy(&out.stdout).to_string()
        + &String::from_utf8_lossy(&out.stderr);
    let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
    {
        let c = core.lock().unwrap();
        for line in lines.iter().rev().take(tail_lines).rev() {
            c.log(key, None, *line);
        }
    }
    if !out.status.success() {
        return Err(format!("命令 {:?} 失败（code={}），见上方输出", argv, out.status.code().unwrap_or(-1)));
    }
    Ok(())
}

fn build_backend(core: &Arc<Mutex<Core>>, key: usize, root: &Path) -> Result<Option<SpawnPlan>, String> {
    // 后端独占 H2 数据库文件：已有实例在跑就直接接管，绝不启动第二个
    // （第二个会因库锁连不上库，报 "Unable to determine Dialect" 崩溃）
    if let Some((pid, port)) = find_running_backend() {
        let mine = core.lock().unwrap().states[key].pid;
        if mine != Some(pid) {
            let agent_running;
            {
                let mut c = core.lock().unwrap();
                if let Some(p) = port {
                    c.states[key].port = Some(p);
                    c.reserved.insert(p);
                }
                c.states[key].pid = Some(pid);
                c.states[key].running = true;
                c.states[key].status = "已在运行".to_string();
                c.log(key, Some(Level::Info), format!(
                    "检测到后端已在运行（PID {pid}{}），直接接管，不再启动第二个（H2 数据库同一时刻只允许一个后端）",
                    port.map(|p| format!("，端口 {p}")).unwrap_or_default()));
                agent_running = c.states[4].running;
            }
            // Agent 的环境变量里带着启动时的后端地址：后端端口确定后若不一致，重启 Agent 对齐
            if agent_running {
                core.lock().unwrap().log(4, Some(Level::Warn),
                    "后端端口已确定，重启 Agent 以对齐服务地址");
                restart_one(core, 4);
            }
            return Ok(None);
        }
    }

    let java = resolve_java();

    // jar 缺失或比源码旧 → 先打包（首次较慢）
    let jar = match find_backend_jar(root) {
        Some(j) if !jar_stale(root, &j) => j,
        maybe_jar => {
            core.lock().unwrap().set_status(key, "构建中");
            core.lock().unwrap().log(key, Some(Level::Warn), if maybe_jar.is_some() {
                "后端 jar 比源码旧（上次构建可能被文件锁跳过），重新执行 mvn -DskipTests package…"
            } else {
                "未找到后端 jar，先执行 mvn -DskipTests package（首次较慢，请等待）…"
            });
            // 与 Python 版一致：用选中的 java 反推 JAVA_HOME，保证 mvn 用对 JDK
            let java_home: Option<String> = java.parent()
                .and_then(|p| p.parent())
                .map(|p| p.to_string_lossy().into_owned())
                .filter(|h| Path::new(h).is_dir());
            let env: Vec<(&'static str, String)> = java_home
                .map(|h| vec![("JAVA_HOME", h)])
                .unwrap_or_default();
            run_prepare(core, key,
                // clean 打包：增量状态文件损坏（编译器切换等）不会卡死构建
                &["cmd", "/C", "mvn", "-q", "-DskipTests", "clean", "package"],
                &root.join("backend"), &env, 12)?;
            find_backend_jar(root).ok_or("打包完成但未找到 jar")?
        }
    };

    if java_major(&java) < 21 {
        core.lock().unwrap().log(key, Some(Level::Warn),
            format!("当前 java 版本低于 21（{}），后端可能无法启动；建议安装 Zulu 21 并设置 JAVA_HOME",
                    java.display()));
    }
    core.lock().unwrap().log(key, Some(Level::Info),
        format!("使用 {}", java.display()));

    // 端口始终显式指定（基准 28080，被保留区间吞掉时已自动漂移）
    let port = core.lock().unwrap().states[key].port.unwrap_or(28080);
    let argv = vec![java.to_string_lossy().into_owned(), "-jar".into(),
                    jar.to_string_lossy().into_owned(),
                    format!("--server.port={port}")];
    Ok(Some(SpawnPlan { argv, cwd: root.join("backend"), env: vec![] }))
}

fn build_frontend(core: &Arc<Mutex<Core>>, key: usize, root: &Path) -> Result<SpawnPlan, String> {
    let vite = root.join("frontend").join("node_modules").join("vite").join("bin").join("vite.js");
    if !vite.is_file() {
        core.lock().unwrap().set_status(key, "安装依赖中");
        core.lock().unwrap().log(key, Some(Level::Warn), "缺少 node_modules，先执行 npm install（仅首次）…");
        run_prepare(core, key, &["cmd", "/C", "npm", "install"], &root.join("frontend"), &[], 8)?;
    }

    let port = core.lock().unwrap().states[key].port.unwrap_or(25173);
    // vite 代理目标跟着后端实际端口走（见 vite.config.ts 的 YKT_BACKEND_PORT）
    let backend_port = core.lock().unwrap().states[2].port.unwrap_or(28080);
    Ok(SpawnPlan {
        argv: vec![node(), vite.to_string_lossy().into_owned(),
                   "--port".into(), port.to_string(), "--strictPort".into()],
        cwd: root.join("frontend"),
        env: vec![("YKT_BACKEND_PORT".to_string(), backend_port.to_string())],
    })
}

// ---------------------------------------------------------------- 批量操作

/// 一键启动：matcher/collector/backend 立即起；后端 HTTP 就绪后再起 frontend + agent。
pub fn start_all(core: &Arc<Mutex<Core>>) {
    // 一键启动前先清孤儿 Agent，避免旧进程带着过期端口一直刷连接失败警告
    kill_orphan_agents(core, 4);
    for key in [0usize, 1, 2] {
        start_service(core, key);
    }
    let core2 = Arc::clone(core);
    thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(120);
        loop {
            // 等后端启动流程（端口解析/接管）结束再读端口，避免把中间态端口写进 Agent 环境
            let (backend_port, backend_starting) = {
                let c = core2.lock().unwrap();
                (c.states[2].port.unwrap_or(28080), c.starting[2])
            };
            if !backend_starting && backend_ready(backend_port) {
                break;
            }
            if Instant::now() >= deadline {
                break;
            }
            thread::sleep(Duration::from_millis(500));
        }
        for key in [3usize, 4] {
            start_service(&core2, key);
        }
    });
}

/// 全部停止，并清扫端口上的平台残留进程（上次强杀启动器留下的孤儿等）。
pub fn stop_all(core: &Arc<Mutex<Core>>) {
    {
        let mut c = core.lock().unwrap();
        for key in 0..SERVICES.len() {
            if c.states[key].running || c.states[key].pid.is_some() {
                stop_one_locked(&mut c, key);
            }
        }
    }
    let core2 = Arc::clone(core);
    thread::spawn(move || {
        thread::sleep(Duration::from_millis(1500)); // 等停止动作先落地
        sweep_orphans(&core2);
    });
}

pub fn stop_one_locked(core: &mut Core, key: usize) {
    if let Some(pid) = core.states[key].pid.take() {
        kill_tree(pid);
    }
    kill_service_port(&mut core.states[key]);
    core.states[key].running = false;
    core.states[key].status = "已停止".to_string();
    core.log(key, Some(Level::Info), "已停止");
}

/// 按端口补刀：python.exe 在 Windows 上存在「启动器父进程 + 真正解释器子进程」结构，
/// 父进程先退时 taskkill /T 会扑空，端口上的真正服务进程要按端口再清一次。
/// 仅在进程曾登记过（确属本平台服务）时调用，防止误杀后来占用端口的外来程序。
fn kill_service_port(state: &mut ServiceState) {
    let Some(port) = state.port else { return };
    if !port_open(port) {
        return;
    }
    if let Some(p) = port_pid(port) {
        if p != std::process::id() {
            kill_tree(p);
        }
    }
}

pub fn stop_one(core: &Arc<Mutex<Core>>, key: usize) {
    let mut c = core.lock().unwrap();
    if c.states[key].running || c.states[key].pid.is_some() {
        stop_one_locked(&mut c, key);
    } else if c.states[key].status != "未启动" && c.states[key].status != "已停止" {
        stop_one_locked(&mut c, key);
    }
}

/// 重启 = 停止 → 等端口释放 → 再启动。
pub fn restart_one(core: &Arc<Mutex<Core>>, key: usize) {
    let core2 = Arc::clone(core);
    thread::spawn(move || {
        stop_one(&core2, key);
        thread::sleep(Duration::from_millis(800));
        start_service(&core2, key);
    });
}

pub fn sweep_orphans(core: &Arc<Mutex<Core>>) {
    let ports: Vec<(usize, Option<u16>)> = core.lock().unwrap()
        .states.iter().enumerate()
        .map(|(i, s)| (i, s.port.or(SERVICES[i].base_port)))
        .collect();
    for (key, port) in ports {
        let Some(port) = port else { continue };
        if port_open(port) {
            if let Some(pid) = port_pid(port) {
                if looks_ours(&cmdline_of(pid)) {
                    kill_tree(pid);
                    core.lock().unwrap().log(key, Some(Level::Warn),
                        format!("已清除端口 {port} 上的平台残留进程（PID {pid}）"));
                }
            }
        }
    }
}

/// 退出前兜底：登记 PID 树 + 各服务端口补刀，全部杀掉（Drop 路径，无日志）。
/// 仅对「登记过 PID 或仍在运行」的服务按端口补刀，避免误杀已停服务端口上的外来程序。
pub fn kill_all_quiet(core: &Arc<Mutex<Core>>) {
    let mut c = core.lock().unwrap();
    for s in c.states.iter_mut() {
        let was_ours = s.pid.is_some() || s.running;
        if let Some(pid) = s.pid.take() {
            kill_tree(pid);
        }
        if was_ours {
            kill_service_port(s);
        }
        s.running = false;
    }
}

/// 打开做题台（前端实际端口或 25173）。
pub fn open_ui(core: &Arc<Mutex<Core>>) {
    let port = core.lock().unwrap().states[3].port.unwrap_or(25173);
    open_browser(&format!("http://localhost:{port}"));
}

// ---------------------------------------------------------------- 退出事件

/// UI 线程收到 Exited 后调用：更新状态与日志。
pub fn handle_exited(core: &Arc<Mutex<Core>>, key: usize, code: Option<i32>) {
    let mut c = core.lock().unwrap();
    c.states[key].pid = None;
    c.states[key].running = false;
    if c.states[key].status != "已停止" {
        c.set_status(key, &format!("已退出({})", code.unwrap_or(-1)));
        c.log(key, Some(Level::Danger), format!("进程退出，code={}", code.unwrap_or(-1)));
    }
}

// ---------------------------------------------------------------- 其它

/// 启动器自身的环境提示（课程名默认值）。
pub fn default_course() -> String {
    env::var("AGENT_COURSE").unwrap_or_else(|_| "高二理论".to_string())
}
