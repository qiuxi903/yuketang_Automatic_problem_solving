//! 雨课堂自动化做题平台 · 启动器（Rust 版）
//!
//! 一个 exe 管全部服务：matcher / collector / backend / frontend / agent。
//! - 不弹服务窗口：子进程隐藏运行（CREATE_NO_WINDOW），日志汇总到日志面板（UTF-8 无乱码）
//! - 每个服务独立 启动 / 停止 / 重启，也可一键启动（后端 HTTP 就绪后再起前端与 Agent）
//! - 后端 jar / 前端依赖缺失时先自动构建再启动
//! - 端口被占（平台残留或外来程序）自动结束占用者腾位；端口被系统保留区间
//!   （Hyper-V 排除段，杀无可杀）时自动向后扫描并联动修正 agent 地址与 vite 代理
//! - 日志限行 1500 行 + 智能滚动（向上翻历史即暂停跟随）
//! - 关闭窗口即停止全部服务
//!
//! 构建：cargo build --release  →  target/release/ykt-launcher.exe
//! 无界面自检：ykt-launcher.exe --smoke（真实拉起全部服务并汇报，结束后自动清理）

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod core;
mod logs;
mod proc;

use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

fn main() {
    let smoke = std::env::args().any(|a| a == "--smoke");

    let Some(root) = proc::find_root() else {
        eprintln!("未找到项目根目录（需同时存在 backend 与 agent 子目录）。请把启动器放在项目内运行。");
        std::process::exit(1);
    };

    let (tx, rx) = mpsc::channel::<core::Msg>();
    let core_state = Arc::new(Mutex::new(core::Core {
        root,
        states: core::SERVICES
            .iter()
            .map(|s| core::ServiceState {
                port: s.base_port,
                pid: None,
                status: "未启动".to_string(),
                running: false,
            })
            .collect(),
        course: core::default_course(),
        starting: [false; 5],
        reserved: std::collections::HashSet::new(),
        tx,
    }));

    if smoke {
        std::process::exit(run_smoke(core_state, rx));
    }

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("雨课堂启动器 · 雨课堂自动化做题平台")
            .with_inner_size([1120.0, 720.0])
            .with_min_inner_size([940.0, 600.0]),
        ..Default::default()
    };

    let run = eframe::run_native(
        "雨课堂启动器",
        options,
        Box::new(move |cc| Ok(Box::new(app::App::new(cc, core_state, rx)))),
    );

    if let Err(e) = run {
        eprintln!("无法创建窗口：{e}");
        std::process::exit(1);
    }
}

/// 无界面自检：真实拉起全部服务，等全部就绪（或超时），打印报告后清理退出。
fn run_smoke(core_state: Arc<Mutex<core::Core>>, rx: mpsc::Receiver<core::Msg>) -> i32 {
    eprintln!("=== SMOKE 开始（无界面自检，最多等 150s） ===");
    core::start_all(&core_state);

    let deadline = Instant::now() + Duration::from_secs(150);
    let mut ok = false;
    loop {
        std::thread::sleep(Duration::from_millis(500));
        // 把服务日志原样吐到 stderr，便于人工核对端口腾位/换口过程
        while let Ok(m) = rx.try_recv() {
            match m {
                core::Msg::Log { key, level, text } => {
                    eprintln!("[{}] {:?}", core::SERVICES[key].short, level.map(|l| l as i32).unwrap_or(-1));
                    eprintln!("[{}] {}", core::SERVICES[key].short, text);
                }
                core::Msg::Exited { key, code } => {
                    eprintln!("[{}] !! 进程退出 code={:?}", core::SERVICES[key].short, code);
                }
            }
        }
        let (running, backend_port, frontend_port) = {
            let c = core_state.lock().unwrap();
            (c.states.iter().filter(|s| s.running).count(),
             c.states[2].port.unwrap_or(28080),
             c.states[3].port.unwrap_or(25173))
        };
        if running == 5 && proc::backend_ready(backend_port) && proc::port_open(frontend_port) {
            eprintln!("=== SMOKE OK：5/5 运行中，后端 :{backend_port} HTTP 就绪，前端 :{frontend_port} 可访问 ===");
            ok = true;
            break;
        }
        if Instant::now() >= deadline {
            eprintln!("=== SMOKE 超时：运行 {running}/5（后端 :{backend_port}，前端 :{frontend_port}） ===");
            break;
        }
    }

    // 终态报告
    let c = core_state.lock().unwrap();
    for (i, s) in c.states.iter().enumerate() {
        eprintln!("REPORT {} port={:?} status={} running={}",
                  core::SERVICES[i].short, s.port, s.status, s.running);
    }
    drop(c);
    core::kill_all_quiet(&core_state);
    core::sweep_orphans(&core_state);
    // 等端口真正释放再退出，避免留下残余
    std::thread::sleep(Duration::from_millis(1200));
    if ok { 0 } else { 1 }
}
