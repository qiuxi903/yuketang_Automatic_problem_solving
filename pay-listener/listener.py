#!/usr/bin/env python3
"""pay-listener —— 免签监听（电脑端）：读微信/支付宝桌面窗口里的收款消息，回调后端自动核销订单。

策略（config.ini 的 strategy，默认 auto）：
  uia  UIA 读取窗口控件树里的会话预览（零注入零风险；微信 3.x 可直接用，4.x 控件树差异需 --probe-uia 校准）
  ocr  截取微信窗口 → RapidOCR 离线识别 → 正则提金额（UIA 读不到内容时的兜底）
  wcf  wcferry 消息钩子（仅微信 3.9.x，注入方案有封号风险，默认不启用）
  auto 先用 uia；连续 5 次读不到窗口/条目自动切 ocr

回调：POST {backend}/api/pay/notify?token=...  body={channel, amountFen, raw}
自检：python listener.py --selftest      只验证金额解析，不联网
校准：python listener.py --probe-uia     打印本机微信窗口的控件树读数，校准 UIA 配置

使用要点：
  1. 微信电脑版保持登录，主窗口可以遮挡但**不能最小化**（最小化后窗口内容读不到）；
  2. 首次运行先 --probe-uia：确认能读到「微信支付」会话条目再挂后台常驻；
  3. 后端侧靠「金额精确匹配 + 10 分钟窗口」防重复核销，监听器重复回调是安全的。
"""

from __future__ import annotations

import configparser
import re
import sys
import time
from pathlib import Path

import requests

CONFIG_PATH = Path(__file__).parent / "config.ini"

# 从收款文本提金额：收款3.47元 / 到账 12.00 元 / ¥3.47 / 支付宝成功收款0.01元
AMOUNT_RE = re.compile(
    r"(?:收款|到账|入账|付[款了])[^0-9]{0,6}(?:¥|￥)?\s*([0-9]{1,7}(?:\.[0-9]{1,2})?)"
    r"|(?:¥|￥)\s*([0-9]{1,7}(?:\.[0-9]{1,2})?)"
)

# 会话条目只要带这些标记就视为收款消息
PAY_MARKERS = ("收款", "到账", "入账", "¥", "￥")


def parse_amount_fen(text: str) -> int | None:
    """从通知文本提取金额（分）；识别不到返回 None。"""
    if not text:
        return None
    m = AMOUNT_RE.search(text.replace(",", "").replace("，", ""))
    if not m:
        return None
    yuan = m.group(1) or m.group(2)
    try:
        return round(float(yuan) * 100)
    except ValueError:
        return None


def load_config() -> configparser.ConfigParser:
    cfg = configparser.ConfigParser()
    if not CONFIG_PATH.exists():
        print(f"[!] 缺少配置文件 {CONFIG_PATH}，请复制 config.example.ini 为 config.ini 并填写")
        sys.exit(1)
    cfg.read(CONFIG_PATH, encoding="utf-8")
    return cfg


class Notifier:
    def __init__(self, backend: str, token: str):
        self.url = backend.rstrip("/") + "/api/pay/notify"
        self.token = token
        self.session = requests.Session()

    def send(self, channel: str, amount_fen: int, raw: str) -> bool:
        for attempt in range(3):
            try:
                r = self.session.post(
                    self.url,
                    params={"token": self.token},
                    json={"channel": channel, "amountFen": amount_fen, "raw": raw},
                    timeout=5,
                )
                if r.status_code == 200:
                    matched = r.json().get("matched", False)
                    print(f"[+] 回调成功 matched={matched} amount={amount_fen}fen raw={raw!r}")
                    return True
                print(f"[!] 回调失败 http={r.status_code} body={r.text[:120]}")
            except requests.RequestException as e:
                print(f"[!] 回调异常（第 {attempt + 1}/3 次）：{e}")
            time.sleep(1.5 * (attempt + 1))
        return False


# ---------------------------------------------------------------- 策略 A：UIA


class UiaStrategy:
    """轮询微信/支付宝主窗口的会话列表，按「会话关键词」盯条目预览文本，
    预览发生变化且含收款标记时触发（同一会话重复收款也能触发，靠后端金额匹配去重）。"""

    def __init__(self, window_keywords: list[str], session_keywords: list[str]):
        import uiautomation as auto  # 延迟导入：ocr-only 用户无需安装

        self.auto = auto
        self.window_keywords = window_keywords
        self.session_keywords = session_keywords
        # 会话关键词 -> 最近一次预览文本；None 值 = 启动基线（首次只见不发）
        self._last: dict[str, str | None] = {kw: None for kw in session_keywords}

    def find_windows(self) -> list:
        result = []
        try:
            for w in self.auto.GetRootControl().GetChildren():
                name = w.Name or ""
                if any(kw in name for kw in self.window_keywords):
                    result.append(w)
        except Exception as e:
            print(f"[!] 枚举桌面窗口失败：{e}")
        return result

    def session_texts(self, window) -> list[str]:
        """从窗口控件树抓 ListItemControl 的 Name（会话条目预览）。深度受限遍历，避免整树过慢。"""
        texts: list[str] = []
        try:
            stack = list(window.GetChildren())
            budget = 400
            while stack and budget > 0:
                budget -= 1
                ctrl = stack.pop(0)
                ctype = ctrl.ControlTypeName
                if ctype == "ListItemControl":
                    if ctrl.Name:
                        texts.append(ctrl.Name)
                else:
                    try:
                        stack.extend(ctrl.GetChildren())
                    except Exception:
                        pass
        except Exception as e:
            print(f"[!] 读取窗口控件失败：{e}")
        return texts

    def poll(self) -> list[tuple[str, str]]:
        """返回 [(channel, raw_text)]：预览有变化且含收款标记的会话条目。"""
        hits: list[tuple[str, str]] = []
        windows = self.find_windows()
        for w in windows:
            name = w.Name or ""
            channel = "alipay" if "支付宝" in name else "wechat"
            for text in self.session_texts(w):
                kw = next((k for k in self.session_keywords if k in text), None)
                if kw is None:
                    continue
                if not any(marker in text for marker in PAY_MARKERS):
                    continue
                prev = self._last.get(kw)
                self._last[kw] = text
                if prev is None:
                    continue  # 启动基线：把既有消息当作历史，不入账
                if text != prev:
                    hits.append((channel, text))
        return hits

    def probe(self) -> None:
        """校准模式：打印桌面窗口与会话条目，帮助确认 UIA 在本机读到了什么。"""
        print("[probe] 桌面顶层窗口：")
        try:
            for w in self.auto.GetRootControl().GetChildren():
                print(f"  - class={w.ClassName!r} name={(w.Name or '')!r}")
        except Exception as e:
            print(f"  枚举失败：{e}")
        for w in self.find_windows():
            texts = self.session_texts(w)
            print(f"[probe] 命中窗口 {(w.Name or '')!r}，读到 {len(texts)} 个列表条目：")
            for t in texts[:40]:
                print(f"    {t!r}")
            if not texts:
                print("    （读不到条目：窗口可能被最小化，或该版本控件树结构不同 → 改用 ocr 策略）")


# ---------------------------------------------------------------- 策略 B：截图 + OCR


class OcrStrategy:
    """定时截微信窗口 → RapidOCR 离线识别 → 找「收款X.XX元」。窗口不能最小化。
    依赖：pip install rapidocr-onnxruntime pillow pywin32（首次运行下载模型）。
    去重：识别文本指纹 + 10 分钟 TTL（与后端订单匹配窗口一致），重复截图同一内容不重复回调。"""

    TTL_SECONDS = 600

    def __init__(self, window_keywords: list[str]):
        try:
            from rapidocr_onnxruntime import RapidOCR

            self.ocr = RapidOCR()
        except ImportError:
            print("[!] 未安装 OCR 依赖：pip install rapidocr-onnxruntime pillow pywin32")
            sys.exit(1)
        self.window_keywords = window_keywords
        self._seen: dict[str, float] = {}

    def _shot_wechat(self):
        import win32gui

        target = None

        def enum_handler(hwnd, _):
            nonlocal target
            if not win32gui.IsWindowVisible(hwnd) or win32gui.IsIconic(hwnd):
                return
            title = win32gui.GetWindowText(hwnd)
            if any(kw in title for kw in self.window_keywords):
                left, top, right, bottom = win32gui.GetWindowRect(hwnd)
                if right - left > 200 and bottom - top > 200:
                    target = (left, top, right, bottom)

        win32gui.EnumWindows(enum_handler, None)
        if not target:
            return None
        from PIL import ImageGrab

        return ImageGrab.grab(bbox=target)

    def poll(self) -> list[tuple[str, str]]:
        img = self._shot_wechat()
        if img is None:
            return []
        result, _ = self.ocr(img)
        hits: list[tuple[str, str]] = []
        now = time.time()
        if result:
            for _box, text, _score in result:
                if not text or not any(marker in text for marker in ("收款", "到账", "入账")):
                    continue
                # 指纹去重：同文本 10 分钟内只回调一次；过期清理
                self._seen = {k: ts for k, ts in self._seen.items() if now - ts < self.TTL_SECONDS}
                import hashlib

                key = hashlib.sha1(text.encode("utf-8")).hexdigest()
                if key in self._seen:
                    continue
                self._seen[key] = now
                hits.append(("wechat", text))
        return hits


# ---------------------------------------------------------------- 策略 C：wcferry（可选，默认不启用）


class WcfStrategy:
    """wcferry 消息钩子：仅支持微信 3.9.x 特定版本，注入方案有封号风险。
    依赖：pip install wcferry（并确认本机微信版本在 wcferry 支持列表内）。"""

    def __init__(self, session_keywords: list[str]):
        try:
            from wcferry import Wcf

            self.wcf = Wcf()
        except Exception as e:
            print(f"[!] wcferry 初始化失败（确认微信版本为 3.9.x 且 pip install wcferry）：{e}")
            sys.exit(1)
        self.session_keywords = session_keywords
        self.wcf.enable_receiving_msg()
        print("[!] wcferry 属注入方案，存在账号风控风险；能不开就别开。")

    def poll(self) -> list[tuple[str, str]]:
        msg = self.wcf.get_msg()  # 阻塞等下一条消息
        if msg is None:
            return []
        content = str(getattr(msg, "content", "") or "")
        if any(kw in content for kw in self.session_keywords) and any(
            marker in content for marker in PAY_MARKERS
        ):
            return [("wechat", content)]
        return []


def main() -> None:
    if "--selftest" in sys.argv:
        samples = [
            ("微信支付收款3.47元（好友收款）", 347),
            ("支付宝成功收款12.00元", 1200),
            ("到账 ¥0.01", 1),
            ("二维码收款-朋友已付款1234.56元", 123456),
            ("你的账户有新的消息", None),
        ]
        for text, expect in samples:
            got = parse_amount_fen(text)
            flag = "✓" if got == expect else "✗"
            print(f"{flag} {text!r} -> {got}fen（期望 {expect}）")
            assert got == expect, text
        print("selftest 全部通过")
        return

    cfg = load_config()
    backend = cfg.get("pay", "backend_url")
    token = cfg.get("pay", "listen_token")
    strategy = cfg.get("pay", "strategy", fallback="auto").lower()
    poll_interval = cfg.getfloat("pay", "poll_interval", fallback=1.5)
    window_keywords = [k.strip() for k in cfg.get("windows", "keywords", fallback="微信,支付宝").split(",") if k.strip()]
    session_keywords = [k.strip() for k in cfg.get("windows", "session_keywords", fallback="微信支付,收款助手,收款小账本").split(",") if k.strip()]

    notifier = Notifier(backend, token)
    uia: UiaStrategy | None = None
    ocr: OcrStrategy | None = None
    wcf: WcfStrategy | None = None
    fail_streak = 0

    if "--probe-uia" in sys.argv:
        uia = UiaStrategy(window_keywords, session_keywords)
        uia.probe()
        return

    print(f"[*] pay-listener 启动：backend={backend} strategy={strategy} poll={poll_interval}s")

    while True:
        try:
            hits: list[tuple[str, str]] = []
            if strategy == "wcf":
                if wcf is None:
                    wcf = WcfStrategy(session_keywords)
                hits = wcf.poll()
            elif strategy in ("uia", "auto"):
                if uia is None:
                    uia = UiaStrategy(window_keywords, session_keywords)
                try:
                    hits = uia.poll()
                    fail_streak = 0
                except Exception as e:
                    fail_streak += 1
                    print(f"[!] UIA 读取失败（连续 {fail_streak} 次）：{e}")
                    if strategy == "auto" and fail_streak >= 5:
                        print("[*] UIA 连续失败，自动切到 ocr 策略")
                        uia = None
                        strategy = "ocr"
                        continue
            elif strategy == "ocr":
                if ocr is None:
                    ocr = OcrStrategy(window_keywords)
                hits = ocr.poll()
            else:
                print(f"[!] 未知策略 {strategy}，可用 auto / uia / ocr / wcf")
                return

            for channel, raw in hits:
                amount = parse_amount_fen(raw)
                if amount is None or amount <= 0:
                    print(f"[?] 捕获到收款类消息但提不出金额：{raw!r}")
                    continue
                print(f"[*] 捕获收款：{raw!r} -> {amount}fen")
                notifier.send(channel, amount, raw)
        except KeyboardInterrupt:
            print("\n[*] 已退出")
            return
        except Exception as e:
            print(f"[!] 轮询异常：{e}")
        time.sleep(poll_interval)


if __name__ == "__main__":
    main()
