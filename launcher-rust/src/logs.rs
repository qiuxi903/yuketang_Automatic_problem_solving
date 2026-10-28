//! 日志层：等级识别、时间戳前缀解析、限行存储。
//!
//! 对应 Python 版的两轮等级识别 + 时间戳配色 + 1500 行限行，全部手写匹配（不引 regex）。

use std::collections::VecDeque;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Level {
    Ok,
    Warn,
    Danger,
    Info,
    Dim,
    Plain,
}

impl Level {
    /// 日志文本颜色（亮色主题：白底上的深色变体，保证可读）。
    pub fn color(self) -> egui::Color32 {
        match self {
            Level::Ok => egui::Color32::from_rgb(0x0f, 0x7b, 0x0f),      // Win11 绿
            Level::Warn => egui::Color32::from_rgb(0x9a, 0x5d, 0x00),    // 深琥珀
            Level::Danger => egui::Color32::from_rgb(0xc4, 0x2b, 0x1c),  // Win11 红
            Level::Info => egui::Color32::from_rgb(0x0b, 0x57, 0xc2),    // 蓝
            Level::Dim => egui::Color32::from_rgb(0x9a, 0x9a, 0x9a),
            Level::Plain => egui::Color32::from_rgb(0x3b, 0x3b, 0x3b),
        }
    }
}

/// 时间戳单独的配色（柔和紫）。
pub const TS_COLOR: egui::Color32 = egui::Color32::from_rgb(0x7a, 0x52, 0xc7);

/// 单行日志：text 里可能带时间戳前缀（渲染时单独配色）。
#[derive(Clone, Debug)]
pub struct LogLine {
    pub level: Level,
    pub text: String,
    /// 时间戳前缀长度（0 = 无）。
    pub ts_len: usize,
}

const MAX_LINES: usize = 1500;

/// 每个页签一份有界日志存储：超限从头部丢弃（数据层限行，UI 无需再裁剪）。
#[derive(Default)]
pub struct LogStore {
    lines: VecDeque<LogLine>,
    /// 收到新行时自增；UI 消费后清零（用于决定是否自动滚动）。
    pub dirty: bool,
}

impl LogStore {
    pub fn push(&mut self, level: Level, text: String) {
        let ts_len = ts_prefix_len(&text);
        self.lines.push_back(LogLine { level, text, ts_len });
        while self.lines.len() > MAX_LINES {
            self.lines.pop_front();
        }
        self.dirty = true;
    }

    pub fn lines(&self) -> &VecDeque<LogLine> {
        &self.lines
    }

    /// 手动清空当前视图日志。
    pub fn clear(&mut self) {
        self.lines.clear();
        self.dirty = false;
    }
}

// —— 等级识别（两轮：先级别词，再异常类名） ——

/// 词边界匹配：ERROR 前后都不是字母数字（避免普通英文句里的 error 误伤）。
fn has_word(s: &str, word: &str) -> bool {
    let bytes = s.as_bytes();
    let pat = word.as_bytes();
    if pat.len() > bytes.len() {
        return false;
    }
    let boundary = |c: u8| c.is_ascii_alphanumeric() || c == b'_';
    for i in 0..=(bytes.len() - pat.len()) {
        if &bytes[i..i + pat.len()] == pat {
            let before_ok = i == 0 || !boundary(bytes[i - 1]);
            let after = i + pat.len();
            let after_ok = after >= bytes.len() || !boundary(bytes[after]);
            if before_ok && after_ok {
                return true;
            }
        }
    }
    false
}

/// Traceback / 异常类名（ValueError、SocketException 等驼峰类名）。
fn looks_like_stacktrace(s: &str) -> bool {
    if s.contains("Traceback") {
        return true;
    }
    // XxxError / XxxException（类名以大写开头且以这两个后缀结尾的独立词）
    s.split(|c: char| !c.is_alphanumeric()).any(|w| {
        w.len() > 6
            && w.chars().next().is_some_and(|c| c.is_uppercase())
            && (w.ends_with("Error") || w.ends_with("Exception"))
    })
}

/// 显式等级直接用；子进程原始行按内容猜等级。
pub fn pick_level(explicit: Option<Level>, text: &str) -> Level {
    if let Some(l) = explicit {
        return l;
    }
    // 只认大写级别词——Spring/uvicorn/logging 的输出都是大写
    if has_word(text, "ERROR") || has_word(text, "CRITICAL") || has_word(text, "FATAL") {
        return Level::Danger;
    }
    if has_word(text, "WARN") || has_word(text, "WARNING") {
        return Level::Warn;
    }
    if has_word(text, "DEBUG") {
        return Level::Dim;
    }
    if has_word(text, "INFO") {
        return Level::Info;
    }
    if looks_like_stacktrace(text) {
        return Level::Danger;
    }
    Level::Plain
}

/// 识别行首时间戳前缀长度：
/// 可选 `[标签] ` 前缀，随后 `YYYY-MM-DD[T ]HH:MM:SS[.fff][ +08:00|Z]`。
/// 覆盖 Spring（2026-10-16T19:22:27.061+08:00）、uvicorn（2026-10-16 19:22:27,061）。
pub fn ts_prefix_len(text: &str) -> usize {
    let mut rest = text;
    let mut offset = 0usize;
    // [标签] 前缀（「全部」页签的行首 [服务名]）
    if rest.starts_with('[') {
        if let Some(end) = rest.find(']') {
            offset += end + 1;
            rest = &rest[end + 1..];
            // 之后允许一段空格
            let ws = rest.len() - rest.trim_start_matches(' ').len();
            offset += ws;
            rest = &rest[ws..];
        } else {
            return 0;
        }
    }
    let b = rest.as_bytes();
    // YYYY-MM-DD（10 字节，固定格式）
    let looks_date = b.len() >= 10
        && b[0..4].iter().all(|c| c.is_ascii_digit())
        && b[4] == b'-'
        && b[5..7].iter().all(|c| c.is_ascii_digit())
        && b[7] == b'-'
        && b[8..10].iter().all(|c| c.is_ascii_digit());
    if !looks_date {
        return 0;
    }
    // 分隔符 T 或空格
    if b.len() < 11 || !(b[10] == b'T' || b[10] == b' ') {
        return 0;
    }
    // HH:MM:SS
    if b.len() < 19
        || !b[11..13].iter().all(|c| c.is_ascii_digit())
        || b[13] != b':'
        || !b[14..16].iter().all(|c| c.is_ascii_digit())
        || b[16] != b':'
        || !b[17..19].iter().all(|c| c.is_ascii_digit())
    {
        return 0;
    }
    let mut end = 19usize;
    // 毫秒/微秒 [.,]fff…
    if end < b.len() && (b[end] == b'.' || b[end] == b',') {
        let frac_start = end + 1;
        let mut i = frac_start;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
        if i > frac_start {
            end = i;
        }
    }
    // 时区：Z 或 ±HH:MM / ±HHMM
    if end < b.len() {
        match b[end] {
            b'Z' | b'z' => end += 1,
            b'+' | b'-' => {
                let tz = &b[end + 1..];
                if tz.len() >= 5
                    && tz[0..2].iter().all(|c| c.is_ascii_digit())
                    && (tz[2] == b':' || tz[2].is_ascii_digit())
                {
                    end += 1 + if tz[2] == b':' { 5 } else { 4 };
                }
            }
            _ => {}
        }
    }
    offset + end
}
