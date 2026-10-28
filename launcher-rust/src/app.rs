//! UI 层：单窗口亮色启动器（扁平 · 简洁 · 现代）。
//!
//! 布局（与旧版三段式完全不同）：
//! - 顶栏：品牌 + 全局操作（全部启动/停止 · 打开做题台 · 自动打开 · 课程名）
//! - 左栏 268px：服务列表（「全部日志」聚合入口 + 每行内嵌 启动/停止/重启 按钮，点击行切换右侧日志）
//! - 右侧主区：日志面板（视图标题 + 跟随开关 + 清空，等宽日志流，白底圆角细描边）
//! - 底栏：运行统计 · 做题台链接（可点打开）· 数据位置
//!
//! 设计语言：浅灰底 + 白面板细描边（无投影）、Win11 蓝 #0067c0、
//! Segoe UI / Cascadia 字体（中文回退微软雅黑）。

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Receiver, TryRecvError};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use egui::{Align, Align2, Color32, Label, Layout, RichText, ScrollArea, Sense, TextEdit, ViewportCommand};

use crate::core::{self, Core, Msg, SERVICES};
use crate::logs::{pick_level, LogStore, Level, TS_COLOR};
use crate::proc::{backend_ready, open_browser, port_open};

// —— 配色：Win11 Fluent 亮色（扁平） ——
const C_BG: Color32 = Color32::from_rgb(0xf3, 0xf3, 0xf3);
const C_PANEL: Color32 = Color32::from_rgb(0xff, 0xff, 0xff);
const C_LOG: Color32 = Color32::from_rgb(0xfc, 0xfc, 0xfd);
const C_TEXT: Color32 = Color32::from_rgb(0x1b, 0x1b, 0x1b);
const C_MUT: Color32 = Color32::from_rgb(0x74, 0x74, 0x74);
const C_LINE: Color32 = Color32::from_rgb(0xe4, 0xe4, 0xe4);
const C_ACC: Color32 = Color32::from_rgb(0x00, 0x67, 0xc0);
const C_ACC_TINT: Color32 = Color32::from_rgb(0xe8, 0xf1, 0xfb);
const C_DANGER: Color32 = Color32::from_rgb(0xc4, 0x2b, 0x1c);
const C_DANGER_TINT: Color32 = Color32::from_rgb(0xfd, 0xf3, 0xf2);
const C_WARN: Color32 = Color32::from_rgb(0x9d, 0x5d, 0x00);
const C_WARN_TINT: Color32 = Color32::from_rgb(0xfd, 0xf6, 0xe7);
const C_OK: Color32 = Color32::from_rgb(0x0f, 0x7b, 0x0f);
const C_GRAY_DOT: Color32 = Color32::from_rgb(0xa6, 0xa6, 0xa6);
const C_HOVER: Color32 = Color32::from_rgb(0xf0, 0xf0, 0xf0);

/// 视图：0 = 全部聚合日志；1..=5 = 对应服务。
type ViewId = usize;

pub struct App {
    core: Arc<Mutex<Core>>,
    rx: Receiver<Msg>,
    /// 页签日志：0=全部，1..=5 各服务。
    logs: Vec<LogStore>,
    /// 当前日志视图（左栏选中项）。
    view: ViewId,
    /// 每个视图的「跟随底部」。
    follow: Vec<bool>,
    course: String,
    auto_open: bool,
    opened: Arc<AtomicBool>,
    confirm_close: bool,
}

impl App {
    pub fn new(cc: &eframe::CreationContext<'_>, core: Arc<Mutex<Core>>, rx: Receiver<Msg>) -> Self {
        setup_fonts(&cc.egui_ctx);
        setup_visuals(&cc.egui_ctx);

        let root = core.lock().unwrap().root.display().to_string();
        let course = core.lock().unwrap().course.clone();
        let mut app = Self {
            core,
            rx,
            logs: (0..6).map(|_| LogStore::default()).collect(),
            view: 0,
            follow: vec![true; 6],
            course,
            auto_open: false,
            opened: Arc::new(AtomicBool::new(false)),
            confirm_close: false,
        };
        for key in 0..5 {
            app.logs[key + 1].push(Level::Plain, format!("项目根目录：{root}"));
        }
        app
    }

    /// 消费 channel：日志入页签存储；退出事件更新服务状态。
    fn drain(&mut self) {
        loop {
            match self.rx.try_recv() {
                Ok(Msg::Log { key, level, text }) => {
                    let short = SERVICES[key].short;
                    let prefixed = format!("[{short}] {text}");
                    self.logs[0].push(pick_level(level, &prefixed), prefixed);
                    self.logs[key + 1].push(pick_level(level, &text), text);
                }
                Ok(Msg::Exited { key, code }) => {
                    core::handle_exited(&self.core, key, code);
                }
                Err(TryRecvError::Empty) | Err(TryRecvError::Disconnected) => break,
            }
        }
    }

    fn running_count(&self) -> usize {
        self.core.lock().unwrap().states.iter().filter(|s| s.running).count()
    }

    fn handle_close_request(&mut self, ctx: &egui::Context) {
        let closing = ctx.input(|i| i.viewport().close_requested());
        if !closing {
            return;
        }
        if self.confirm_close {
            return; // 确认窗打开中（已 CancelClose），等用户选择
        }
        if self.running_count() > 0 {
            ctx.send_viewport_cmd(ViewportCommand::CancelClose);
            self.confirm_close = true;
        } else {
            core::kill_all_quiet(&self.core);
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.handle_close_request(ctx);
        self.drain();
        // 课程名实时同步给 Core（Agent 启动时作为 AGENT_COURSE 注入）
        self.core.lock().unwrap().course = self.course.clone();

        self.top_bar(ctx);
        self.services_panel(ctx);
        self.log_panel(ctx);
        self.status_bar(ctx);

        if self.confirm_close {
            self.close_confirm_window(ctx);
        }
        ctx.request_repaint_after(Duration::from_millis(150));
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        // 关窗即停：无论走到哪条退出路径，都兜底清理子进程
        core::kill_all_quiet(&self.core);
    }
}

impl App {
    // —— 顶栏：品牌 + 全局操作 ——
    fn top_bar(&mut self, ctx: &egui::Context) {
        egui::TopBottomPanel::top("topbar")
            .frame(egui::Frame::default().fill(C_BG).inner_margin(egui::Margin::symmetric(16.0, 10.0)))
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    // 品牌标
                    let (r, _) = ui.allocate_exact_size([28.0, 28.0].into(), Sense::hover());
                    ui.painter().rect_filled(r, 8.0, C_ACC);
                    ui.painter().text(
                        r.center(), Align2::CENTER_CENTER, "雨",
                        egui::FontId::proportional(14.0), Color32::WHITE);
                    ui.add_space(2.0);
                    ui.vertical(|ui| {
                        ui.add_space(1.0);
                        ui.label(RichText::new("雨课堂启动器").size(14.5).strong().color(C_TEXT));
                        ui.label(RichText::new("自动化做题平台").size(9.5).color(C_MUT));
                    });
                    ui.add_space(18.0);

                    // 全局操作
                    if ui.add(btn_pill("▶ 全部启动", Color32::WHITE, C_ACC, false)).clicked() {
                        core::start_all(&self.core);
                        if self.auto_open {
                            let core = Arc::clone(&self.core);
                            let opened = Arc::clone(&self.opened);
                            thread::spawn(move || {
                                // 后端与前端都真正可用才打开浏览器（最多等 3 分钟）
                                for _ in 0..360 {
                                    let (backend_port, port) = {
                                        let c = core.lock().unwrap();
                                        (c.states[2].port.unwrap_or(28080),
                                         c.states[3].port.unwrap_or(25173))
                                    };
                                    if backend_ready(backend_port) && port_open(port) {
                                        if !opened.swap(true, Ordering::Relaxed) {
                                            open_browser(&format!("http://localhost:{port}"));
                                        }
                                        return;
                                    }
                                    thread::sleep(Duration::from_millis(500));
                                }
                            });
                        }
                    }
                    if ui.add(btn_pill("■ 全部停止", C_DANGER, C_DANGER_TINT, false)).clicked() {
                        core::stop_all(&self.core);
                    }
                    if ui.add(btn_pill("打开做题台", C_TEXT, C_PANEL, true)).clicked() {
                        core::open_ui(&self.core);
                    }
                    ui.checkbox(&mut self.auto_open, RichText::new("就绪后自动打开").color(C_MUT).size(12.0));

                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        ui.label(RichText::new("（重启 Agent 生效）").color(C_MUT).size(10.0));
                        ui.add(TextEdit::singleline(&mut self.course).desired_width(96.0));
                        ui.label(RichText::new("课程名").color(C_MUT).size(12.0));
                    });
                });
            });
    }

    // —— 左栏：服务列表（每行内嵌操作按钮，点击行切换右侧日志视图） ——
    fn services_panel(&mut self, ctx: &egui::Context) {
        egui::SidePanel::left("services")
            .resizable(false)
            .exact_width(268.0)
            .frame(egui::Frame::default().fill(C_BG).inner_margin(egui::Margin::symmetric(12.0, 4.0)))
            .show(ctx, |ui| {
                ui.label(RichText::new("服务").size(10.5).strong().color(C_MUT));

                ScrollArea::vertical()
                    .auto_shrink([false, false])
                    .show(ui, |ui| {
                        ui.add_space(4.0);

                        // 「全部」聚合视图入口
                        self.all_row(ui);

                        ui.add_space(6.0);
                        for key in 0..SERVICES.len() {
                            self.service_row(ui, key);
                            ui.add_space(4.0);
                        }
                    });
            });
    }

    /// 「全部日志」行。
    fn all_row(&mut self, ui: &mut egui::Ui) {
        let selected = self.view == 0;
        let (fill, stroke) = row_style(selected, false);
        egui::Frame::default()
            .fill(fill)
            .stroke(stroke)
            .rounding(10.0)
            .inner_margin(egui::Margin::symmetric(10.0, 8.0))
            .show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.horizontal(|ui| {
                    let (r, _) = ui.allocate_exact_size([8.0, 8.0].into(), Sense::hover());
                    ui.painter().circle_filled(r.center(), 4.0, C_ACC);
                    ui.add_space(4.0);
                    let resp = ui.selectable_label(selected,
                        RichText::new("全部日志").size(13.0).strong().color(C_TEXT));
                    if resp.clicked() {
                        self.view = 0;
                    }
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        ui.label(RichText::new("聚合视图").size(10.5).color(C_MUT));
                    });
                });
            });
    }

    /// 单个服务行：状态点 + 名称（点击选中）+ 端口/PID + 启动/停止/重启按钮。
    fn service_row(&mut self, ui: &mut egui::Ui, key: usize) {
        let (status, port, pid, running, starting) = {
            let c = self.core.lock().unwrap();
            let s = &c.states[key];
            (s.status.clone(), s.port.or(SERVICES[key].base_port), s.pid, s.running, c.starting[key])
        };
        let selected = self.view == key + 1;
        let dot = status_dot_color(&status, starting);

        let (fill, stroke) = row_style(selected, false);
        egui::Frame::default()
            .fill(fill)
            .stroke(stroke)
            .rounding(10.0)
            .inner_margin(egui::Margin::symmetric(10.0, 8.0))
            .show(ui, |ui| {
                ui.set_width(ui.available_width());

                // 第一行：点 + 名称 + 操作按钮
                ui.horizontal(|ui| {
                    let (r, _) = ui.allocate_exact_size([9.0, 9.0].into(), Sense::hover());
                    ui.painter().circle_filled(r.center(), 4.5, dot);
                    ui.add_space(5.0);
                    let resp = ui.selectable_label(selected,
                        RichText::new(SERVICES[key].short).size(13.0).strong().color(C_TEXT));
                    if resp.clicked() {
                        self.view = key + 1;
                    }

                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        // 重启：ghost 小按钮
                        if ui.add(small_btn("↻", C_TEXT, Color32::TRANSPARENT, true)).clicked() {
                            core::restart_one(&self.core, key);
                        }
                        if running {
                            if ui.add(small_btn("停止", C_DANGER, C_DANGER_TINT, false)).clicked() {
                                core::stop_one(&self.core, key);
                            }
                        } else {
                            let fill = if starting { C_GRAY_DOT } else { C_ACC };
                            let btn = small_btn("启动", Color32::WHITE, fill, false);
                            if ui.add_enabled(!starting, btn).clicked() {
                                core::start_service(&self.core, key);
                            }
                        }
                    });
                });

                // 第二行：端口 · 状态 · PID
                let mut meta = String::new();
                if let Some(p) = port {
                    meta.push_str(&format!(":{p} · "));
                }
                meta.push_str(&status);
                if let Some(p) = pid {
                    meta.push_str(&format!(" · PID {p}"));
                }
                let meta_r = ui.selectable_label(selected,
                    RichText::new(meta).size(10.5).color(if selected { C_ACC } else { C_MUT }));
                if meta_r.clicked() {
                    self.view = key + 1;
                }
            });
    }

    // —— 右侧主区：日志面板 ——
    fn log_panel(&mut self, ctx: &egui::Context) {
        egui::CentralPanel::default()
            .frame(egui::Frame::default().fill(C_BG).inner_margin(egui::Margin::symmetric(4.0, 4.0)))
            .show(ctx, |ui| {
                // 向上滚动即暂停跟随
                let scroll_up = ctx.input(|i| i.raw_scroll_delta.y > 0.5);
                if scroll_up {
                    self.follow[self.view] = false;
                }
                let follow = self.follow[self.view];
                let title = if self.view == 0 { "全部日志" } else { SERVICES[self.view - 1].title };

                egui::Frame::default()
                    .fill(C_PANEL)
                    .stroke(egui::Stroke::new(1.0_f32, C_LINE))
                    .inner_margin(egui::Margin::same(0.0))
                    .rounding(10.0)
                    .show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        // 面板头：视图名 + 跟随开关 + 清空
                        ui.horizontal(|ui| {
                            ui.add_space(14.0);
                            ui.label(RichText::new(title).size(12.5).strong().color(C_TEXT));
                            if self.view == 0 {
                                ui.label(RichText::new("所有服务按时间汇聚").size(10.5).color(C_MUT));
                            }
                            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                ui.add_space(8.0);
                                if ui.add(small_btn("清空", C_MUT, Color32::TRANSPARENT, true)).clicked() {
                                    self.logs[self.view].clear();
                                }
                                let (label, color, fill) = if follow {
                                    ("● 跟随中", C_OK, Color32::TRANSPARENT)
                                } else {
                                    ("○ 跟随已暂停", C_WARN, C_WARN_TINT)
                                };
                                if ui.add(small_btn(label, color, fill, true)).clicked() {
                                    self.follow[self.view] = true;
                                }
                            });
                        });
                        ui.add_space(4.0);
                        ui.separator();

                        // 日志流
                        ScrollArea::both()
                            .auto_shrink([false, false])
                            .stick_to_bottom(follow)
                            .show(ui, |ui| {
                                ui.add_space(6.0);
                                let store = &self.logs[self.view];
                                if store.lines().is_empty() {
                                    ui.add_space(40.0);
                                    ui.with_layout(Layout::top_down_justified(Align::Center), |ui| {
                                        ui.label(RichText::new("暂无日志").size(13.0).color(C_MUT));
                                        ui.label(RichText::new("启动服务后，输出会实时滚动到这里").size(11.0).color(C_MUT));
                                    });
                                    ui.add_space(40.0);
                                    return;
                                }
                                for line in store.lines() {
                                    ui.horizontal(|ui| {
                                        if line.ts_len > 0 {
                                            let ts = &line.text[..line.ts_len];
                                            let rest = &line.text[line.ts_len..];
                                            ui.add(Label::new(RichText::new(ts).monospace().size(11.5).color(TS_COLOR)));
                                            ui.add(Label::new(RichText::new(rest).monospace().size(11.5).color(line.level.color())));
                                        } else {
                                            ui.add(Label::new(RichText::new(&line.text).monospace().size(11.5).color(line.level.color())));
                                        }
                                    });
                                }
                                ui.add_space(8.0);
                            });
                    });
            });
    }

    // —— 底部状态栏 ——
    fn status_bar(&mut self, ctx: &egui::Context) {
        egui::TopBottomPanel::bottom("statusbar")
            .frame(egui::Frame::default().fill(C_BG).inner_margin(egui::Margin::symmetric(16.0, 6.0)))
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    let running = self.running_count();
                    let dot = if running == 0 { C_GRAY_DOT } else { C_OK };
                    let (r, _) = ui.allocate_exact_size([7.0, 7.0].into(), Sense::hover());
                    ui.painter().circle_filled(r.center(), 3.5, dot);
                    ui.label(RichText::new(if running == 0 {
                        "未启动任何服务".to_string()
                    } else {
                        format!("运行 {running}/5")
                    }).size(11.5).color(C_MUT));

                    let frontend_port = self.core.lock().unwrap().states[3].port.unwrap_or(25173);
                    let url = format!("http://localhost:{frontend_port}");
                    let link = ui.add(Label::new(
                        RichText::new(format!("做题台 {url}")).size(11.5).color(C_ACC))
                        .sense(Sense::click()));
                    if link.clicked() {
                        open_browser(&url);
                    }
                    if link.hovered() {
                        ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
                    }

                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        ui.label(RichText::new("数据保存在 backend\\data\\").size(11.0).color(C_MUT));
                    });
                });
            });
    }

    // —— 退出确认 ——
    fn close_confirm_window(&mut self, ctx: &egui::Context) {
        let mut open = self.confirm_close;
        egui::Window::new(RichText::new("退出确认").strong().color(C_TEXT))
            .open(&mut open)
            .collapsible(false)
            .resizable(false)
            .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
            .frame(egui::Frame::default().fill(C_PANEL).stroke(egui::Stroke::new(1.0_f32, C_LINE))
                .inner_margin(egui::Margin::same(18.0)).rounding(12.0))
            .show(ctx, |ui| {
                ui.label(RichText::new(
                    "关闭窗口将停止全部服务（数据已保存在 backend\\data\\）。确定退出？")
                    .size(13.0).color(C_TEXT));
                ui.add_space(12.0);
                ui.horizontal(|ui| {
                    if ui.add(btn_pill("确定退出", Color32::WHITE, C_DANGER, false)).clicked() {
                        self.confirm_close = false;
                        core::stop_all(&self.core);
                        ctx.send_viewport_cmd(ViewportCommand::Close);
                    }
                    if ui.add(btn_pill("取消", C_TEXT, C_PANEL, true)).clicked() {
                        self.confirm_close = false;
                    }
                });
            });
        // 点 × 关闭确认窗 = 取消
        if !open {
            self.confirm_close = false;
        }
    }
}

// ---------------------------------------------------------------- 小部件与样式

/// 行背景：选中 = 蓝 tint；未选中 = 白；描边极淡。
fn row_style(selected: bool, hover: bool) -> (Color32, egui::Stroke) {
    if selected {
        (C_ACC_TINT, egui::Stroke::new(1.0_f32, C_ACC_TINT))
    } else if hover {
        (C_HOVER, egui::Stroke::new(1.0_f32, C_LINE))
    } else {
        (C_PANEL, egui::Stroke::new(1.0_f32, C_LINE))
    }
}

/// 顶栏胶囊按钮。
fn btn_pill(text: &str, fg: Color32, bg: Color32, border: bool) -> egui::Button<'_> {
    let mut b = egui::Button::new(RichText::new(text).size(12.5).color(fg))
        .fill(bg)
        .rounding(8.0)
        .min_size([0.0, 30.0].into());
    if border {
        b = b.stroke(egui::Stroke::new(1.0_f32, C_LINE));
    }
    b
}

/// 行内/面板头小按钮（扁平：透明或 tint 底）。
fn small_btn(text: &str, fg: Color32, bg: Color32, border: bool) -> egui::Button<'_> {
    let mut b = egui::Button::new(RichText::new(text).size(11.0).color(fg))
        .fill(bg)
        .rounding(6.0)
        .min_size([0.0, 22.0].into());
    if border {
        b = b.stroke(egui::Stroke::new(1.0_f32, C_LINE));
    }
    b
}

fn status_dot_color(status: &str, starting: bool) -> Color32 {
    if starting {
        return C_WARN;
    }
    match status {
        "运行中" | "已启动" | "已在运行" => C_OK,
        "构建中" | "安装依赖中" | "准备浏览器中" => C_WARN,
        "构建失败" | "安装失败" | "准备失败" | "启动失败" => C_DANGER,
        "端口被占" => Color32::from_rgb(0xea, 0x58, 0x0c),
        s if s.starts_with("已退出") => C_DANGER,
        _ => C_GRAY_DOT, // 未启动 / 已停止
    }
}

/// 加载 Win11 风格字体：Segoe UI（界面）+ Cascadia（日志等宽），中文回退微软雅黑。
fn setup_fonts(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    let load = |fonts: &mut egui::FontDefinitions, name: &str, path: &str, prop: bool| -> bool {
        match std::fs::read(path) {
            Ok(bytes) => {
                fonts.font_data.insert(name.to_string(), egui::FontData::from_owned(bytes).into());
                if prop {
                    fonts.families.entry(egui::FontFamily::Proportional).or_default().insert(0, name.to_string());
                    // 日志区是 Monospace 族：中文回退到雅黑
                    fonts.families.entry(egui::FontFamily::Monospace).or_default().push(name.to_string());
                } else {
                    fonts.families.entry(egui::FontFamily::Monospace).or_default().insert(0, name.to_string());
                }
                true
            }
            Err(_) => false,
        }
    };
    // 界面字体：Segoe UI Variable → Segoe UI → 雅黑
    let ui_font = ["C:\\Windows\\Fonts\\SegUIVar.ttf", "C:\\Windows\\Fonts\\segoeui.ttf"]
        .iter()
        .find(|p| std::path::Path::new(p).is_file())
        .cloned();
    if let Some(p) = ui_font {
        load(&mut fonts, "segoe", &p, true);
    }
    load(&mut fonts, "msyh", r"C:\Windows\Fonts\msyh.ttc", true);
    if !fonts.families.get(&egui::FontFamily::Monospace).unwrap().contains(&"msyh".to_string()) {
        load(&mut fonts, "msyh2", r"C:\Windows\Fonts\msyh.ttf", true);
    }
    // 等宽：Cascadia Mono → Cascadia Code → Consolas
    let mono = ["C:\\Windows\\Fonts\\CascadiaMono.ttf", "C:\\Windows\\Fonts\\CascadiaCode.ttf",
                "C:\\Windows\\Fonts\\consola.ttf"]
        .iter()
        .find(|p| std::path::Path::new(p).is_file())
        .cloned();
    if let Some(p) = mono {
        load(&mut fonts, "mono", &p, false);
    }
    ctx.set_fonts(fonts);
}

fn setup_visuals(ctx: &egui::Context) {
    let mut v = egui::Visuals::light();
    v.panel_fill = C_BG;
    v.extreme_bg_color = C_LOG;
    v.window_fill = C_PANEL;
    v.override_text_color = Some(C_TEXT);
    v.selection.bg_fill = C_ACC_TINT;
    v.selection.stroke = egui::Stroke::NONE;
    v.window_stroke = egui::Stroke::new(1.0_f32, C_LINE);
    v.window_rounding = 12.0.into();
    v.menu_rounding = 8.0.into();
    v.widgets.noninteractive.bg_fill = C_LOG;
    v.widgets.noninteractive.fg_stroke = egui::Stroke::new(1.0_f32, C_TEXT);
    ctx.set_visuals(v);
}
