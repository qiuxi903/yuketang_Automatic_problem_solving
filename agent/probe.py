"""选择器校准探针：有头浏览器 + 持久化登录态，分步 dump 页面结构并截图。

用途：首次真实登录后，用它确认 ykt_driver.py 里每个选择器是否命中，再据输出改代码。

用法（在 agent 目录下执行）：
    python probe.py login
    python probe.py course --course "目标课程名"
    python probe.py papers
    python probe.py exam --course-id 123 --exam-id 456
    python probe.py result --exam-id 456

可选参数：
    --wait N     login 步骤等待扫码的秒数，默认 240
    --hold N     dump 后保持浏览器打开的秒数，默认 0（便于人工再操作一次后观察）

首次运行会在 agent\\runtime\\profile 建立持久化目录，扫码登录一次后续复用，不必反复扫。
每次运行会把整页截图存到 agent\\runtime\\shots\\<步骤>.png。
"""

import argparse
import os
import re
import sys
import time

from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
PROFILE = os.path.join(HERE, "runtime", "profile")
SHOTS = os.path.join(HERE, "runtime", "shots")

INDEX = "https://www.yuketang.cn/v2/web/index"
TRANS = "https://www.yuketang.cn/v2/web/trans/{c}/{e}?status=0&isFrom=2"
EXAM = "https://examination.xuetangx.com/exam/{e}?isFrom=2"
RESULT = "https://examination.xuetangx.com/result/{e}?isFrom=2"

LOGIN_SELECTORS = ["text=我听的课", "text=我教的课", ".user-name", ".nickname", ".avatar-name"]
QR_SELECTORS = ["img[src*='qrcode']", "img[src*='qr']", "canvas", ".qrcode", ".login-qrcode",
                ".qrcode-img", "[class*='qrcode'] img"]


def shot(page, name):
    os.makedirs(SHOTS, exist_ok=True)
    path = os.path.join(SHOTS, name + ".png")
    try:
        page.screenshot(path=path, full_page=True)
        print("截图已保存：%s" % path)
    except Exception as exc:
        print("截图失败：%s" % exc)
    return path


def report(page, title, selectors):
    print("-" * 64)
    print(title)
    for sel in selectors:
        try:
            loc = page.locator(sel)
            n = loc.count()
        except Exception as exc:
            print("  %-30s 定位异常 %s" % (sel, exc))
            continue
        vis = 0
        for i in range(min(n, 10)):
            try:
                if loc.nth(i).is_visible():
                    vis += 1
            except Exception:
                pass
        flag = "命中" if vis else ("存在但不可见" if n else "未命中")
        print("  %-30s count=%d visible=%d  %s" % (sel, n, vis, flag))


def is_logged_in(page):
    for sel in LOGIN_SELECTORS:
        try:
            if page.locator(sel).count():
                return True
        except Exception:
            pass
    return False


def dump_login(page):
    print("当前地址：", page.url)
    report(page, "登录态候选选择器", LOGIN_SELECTORS)
    report(page, "二维码候选选择器", QR_SELECTORS)
    if is_logged_in(page):
        print("判定：已登录。")
    else:
        print("判定：未登录，需要在登录页核对二维码选择器。")


def dump_qr_detail(page):
    js = """
    () => {
      const out = [];
      document.querySelectorAll('img').forEach((im, i) => {
        const src = im.getAttribute('src') || '';
        const cls = String(im.className || '');
        if (!/qr/i.test(src) && !/qr/i.test(cls)) return;
        const r = im.getBoundingClientRect();
        out.push({idx: i, cls: cls, w: Math.round(r.width), h: Math.round(r.height),
                  srcHead: src.slice(0, 90)});
      });
      return out;
    }
    """
    rows = page.evaluate(js)
    print("-" * 64)
    print("二维码 img 明细（尺寸约 150 至 260 的方形那个才是二维码）：")
    for r in rows:
        print("  img#%d class=%s 宽=%d 高=%d src=%s" % (r["idx"], r["cls"], r["w"], r["h"], r["srcHead"]))


def refresh_if_expired(page):
    """二维码约 2 分钟过期，过期后页面出现「重新获取」，点它换新码。"""
    try:
        el = page.get_by_text("重新获取", exact=False).first
        if el.count() and el.is_visible():
            el.click()
            page.wait_for_timeout(2000)
            return True
    except Exception:
        pass
    return False


def dismiss_popups(page):
    """关闭登录后常见的引导弹窗，避免遮挡点击。"""
    for text in ("我知道了", "知道了", "关闭", "跳过"):
        try:
            el = page.get_by_text(text, exact=True).first
            if el.count() and el.is_visible():
                el.click()
                page.wait_for_timeout(600)
                return True
        except Exception:
            continue
    return False


def dump_course(page, course):
    report(page, "课程入口候选选择器", ["text=我听的课", "text=我教的课"])
    if not course:
        js = """
        () => {
          const main = document.querySelector('main') || document.querySelector('#app') || document.body;
          return (main.innerText || '').split('\\n')
            .map(s => s.trim())
            .filter(s => s && s.length <= 40)
            .slice(0, 60);
        }
        """
        print("-" * 64)
        print("页面文本行（用于找课程名，取其中之一作为 --course）：")
        for line in page.evaluate(js):
            print("  " + line)
        return
    js = """
    (name) => {
      const out = [];
      document.querySelectorAll('div,a,li,span').forEach(el => {
        const t = (el.innerText || '').trim();
        if (!t || t.length > 40) return;
        if (!t.includes(name)) return;
        out.push({tag: el.tagName, cls: el.className, text: t});
      });
      return out.slice(0, 15);
    }
    """
    print("-" * 64)
    print("包含「%s」的元素（取最内层、text 最短的那个作为点击目标）：" % course)
    for item in page.evaluate(js, course):
        print("  %-6s class=%-30s text=%s" % (item["tag"], str(item["cls"])[:30], item["text"]))


def dump_papers(page):
    js = """
    () => {
      const texts = ['开始答题','继续答题','进入考试','查看'];
      const trans = /\\/v2\\/web\\/trans\\/(\\d+)\\/(\\d+)/;
      const out = [];
      document.querySelectorAll('div,a,li,button,span').forEach(el => {
        const t = (el.innerText || '').trim();
        if (!t || t.length > 30) return;
        if (!texts.some(x => t.includes(x))) return;
        const card = el.closest('div') || el;
        let examId = null, courseId = null;
        for (const a of card.querySelectorAll('a[href]')) {
          const m = trans.exec(a.getAttribute('href') || '');
          if (m) { courseId = m[1]; examId = m[2]; break; }
        }
        out.push({
          btnText: t,
          btnClass: el.className,
          cardText: (card.innerText || '').replace(/\\n/g, ' / ').slice(0, 120),
          examId, courseId
        });
      });
      return out;
    }
    """
    rows = page.evaluate(js)
    print("-" * 64)
    print("试卷卡片候选，共 %d 条：" % len(rows))
    for r in rows:
        print("  按钮=%s" % r["btnText"])
        print("    按钮class=%s" % r["btnClass"])
        print("    卡片文本=%s" % r["cardText"])
        print("    courseId=%s examId=%s" % (r["courseId"], r["examId"]))
    if rows and not any(r["examId"] for r in rows):
        print("提示：没有从 href 提取到 examId，需在按钮点击后用 expect_popup 或监听 window.open 拿地址。")


def dump_exam(page, course_id, exam_id):
    page.goto(TRANS.format(c=course_id, e=exam_id), wait_until="domcontentloaded")
    page.wait_for_timeout(2500)
    try:
        page.get_by_text("开始", exact=True).first.click(timeout=3000)
        page.wait_for_timeout(2000)
    except Exception:
        pass
    print("答题页地址：", page.url)
    report(page, "答题页选择器", ["div.exam-font", "label.el-radio", ".radioText",
                                  "label.el-radio.is-checked", "h4.exam-font"])
    js = """
    () => {
      const blocks = [...document.querySelectorAll('div.exam-font')];
      return blocks.slice(0, 3).map(b => ({
        cls: b.className,
        head: (b.innerText || '').split('\\n').slice(0, 2).join(' / ').slice(0, 100),
        optCount: b.querySelectorAll('label.el-radio').length,
        opts: [...b.querySelectorAll('label.el-radio')].slice(0, 4).map(l => {
          const t = l.querySelector('.radioText');
          return ((t ? t.innerText : l.innerText) || '').trim().slice(0, 40);
        }),
        html: b.outerHTML.slice(0, 500)
      }));
    }
    """
    samples = page.evaluate(js)
    print("-" * 64)
    print("题目块样本，div.exam-font 共 %d 个：" % page.locator("div.exam-font").count())
    for i, s in enumerate(samples):
        print("  第 %d 题 class=%s" % (i + 1, s["cls"]))
        print("    前两行=%s" % s["head"])
        print("    选项数=%d 选项=%s" % (s["optCount"], s["opts"]))
        print("    片段=%s" % s["html"])


def dump_result(page, exam_id):
    page.goto(RESULT.format(e=exam_id), wait_until="domcontentloaded")
    page.wait_for_timeout(3000)
    print("结果页地址：", page.url)
    report(page, "结果页选择器", [".item-body", "h4.exam-font", ".radioText", ".dot-success"])
    js = """
    () => {
      const blocks = [...document.querySelectorAll('.item-body')];
      const parse = b => {
        const stem = ((b.querySelector('h4.exam-font') || b).innerText || '').trim().slice(0, 60);
        const ok = b.querySelector('.dot-success');
        let answerByDom = '';
        if (ok) {
          const row = ok.closest('label') || ok.parentElement;
          const t = row ? row.querySelector('.radioText') : null;
          answerByDom = ((t ? t.innerText : (row ? row.innerText : '')) || '').trim().slice(0, 40);
        }
        const m = (b.innerText || '').match(/正确答案[:：]\\s*([A-Z])/);
        const opts = [...b.querySelectorAll('label.el-radio')].map(l => {
          const t = l.querySelector('.radioText');
          return ((t ? t.innerText : l.innerText) || '').trim().slice(0, 30);
        });
        return {stem, answerByDom, answerLetter: m ? m[1] : null, optCount: opts.length,
                successCount: b.querySelectorAll('.dot-success').length, opts: opts.slice(0, 4)};
      };
      const first = blocks[0];
      return {
        total: blocks.length,
        samples: blocks.slice(0, 3).map(parse),
        firstHtml: first ? first.outerHTML.slice(0, 1800) : ''
      };
    }
    """
    data = page.evaluate(js)
    print("-" * 64)
    print("题目块总数：%d" % data["total"])
    for i, s in enumerate(data["samples"]):
        print("  #%d 选项数=%d dot-success=%d 正确答案字母=%s" % (i + 1, s["optCount"], s["successCount"], s["answerLetter"]))
        print("     题干=%s" % s["stem"])
        print("     DOM取到正确项文本=%s" % s["answerByDom"])
        print("     选项=%s" % s["opts"])
    print("-" * 64)
    print("首个 item-body HTML：")
    print(data["firstHtml"])


def dump_logs(page):
    """解析学习日志：按 div.activity-box 取卡片，提取时间/名称/满分/题数/得分与跳转线索。"""
    js = """
    () => {
      const trans = /\\/v2\\/web\\/trans\\/(\\d+)\\/(\\d+)/;
      const cards = [...document.querySelectorAll('div.activity-box')].map(box => {
        const time = ((box.querySelector('.time') || {}).innerText || '').trim();
        const info = box.querySelector('.activity-info');
        let name = '';
        if (info) {
          const tag = info.querySelector('.tag');
          name = (info.innerText || '').replace(tag ? tag.innerText : '', '').trim();
        }
        const sub = ((box.querySelector('.sub-info') || {}).innerText || '').replace(/\\s+/g, ' ').trim();
        const right = [...box.querySelectorAll('div,span')]
          .map(e => (e.innerText || '').trim())
          .filter(t => /^\\d+$/.test(t) || t === '得分');
        const links = [...box.querySelectorAll('a[href]')].map(a => a.getAttribute('href'));
        const attrs = [];
        [box, ...box.querySelectorAll('*')].slice(0, 12).forEach(el => {
          for (const at of el.attributes) {
            if (/^(data-|href|onclick|class)/.test(at.name)) {
              attrs.push(el.tagName + '@' + at.name + '=' + String(at.value).slice(0, 80));
            }
          }
        });
        let courseId = null, examId = null;
        for (const v of [...links, ...attrs]) {
          const m = trans.exec(v || '');
          if (m) { courseId = m[1]; examId = m[2]; break; }
        }
        return {time, name, sub, right, links, courseId, examId};
      });
      const first = document.querySelector('div.activity-box');
      return {cards, firstHtml: first ? first.outerHTML.slice(0, 1400) : ''};
    }
    """
    data = page.evaluate(js)
    cards = data["cards"]
    print("-" * 64)
    print("activity-box 卡片，共 %d 张：" % len(cards))
    for i, c in enumerate(cards):
        print("  #%d 时间=%s 名称=%s" % (i + 1, c["time"], c["name"]))
        print("      %s | 右侧=%s | links=%s" % (c["sub"], c["right"], c["links"]))
        print("      courseId=%s examId=%s" % (c["courseId"], c["examId"]))
    if data["firstHtml"]:
        print("-" * 64)
        print("首张卡片完整 HTML（用于定点击目标）：")
        print(data["firstHtml"])


def dump_answer_or_result(page, action):
    """答题页/结果页通用 dump：同时探测题目块、选项、得分与正确项标记。"""
    print("-" * 64)
    print("当前地址：", page.url)
    report(page, "答题页候选选择器", ["div.exam-font", "label.el-radio", ".radioText",
                                      "label.el-radio.is-checked", "h4.exam-font",
                                      ".subject", ".option-item", ".question-item"])
    report(page, "结果页候选选择器", [".item-body", ".dot-success", ".radioText", ".exam-font"])
    js = """
    () => {
      const score = (document.body.innerText.match(/(\\d+)\\s*\\/\\s*(\\d+)/) || [])[0] || null;
      const blocks = [...document.querySelectorAll('.item-body, div.exam-font, .question-item, .subject')];
      return {
        score,
        blockCount: blocks.length,
        samples: blocks.slice(0, 3).map(b => ({
          cls: String(b.className || '').slice(0, 60),
          head: (b.innerText || '').split('\\n').slice(0, 2).join(' / ').slice(0, 90),
          opts: b.querySelectorAll('label.el-radio, .option-item, .radioText').length,
          hasCorrect: !!b.querySelector('.dot-success')
        }))
      };
    }
    """
    data = page.evaluate(js)
    print("得分候选：%s" % data["score"])
    print("题目块候选，共 %d 个：" % data["blockCount"])
    for i, s in enumerate(data["samples"]):
        print("  #%d class=%s 选项数=%d 正确标记=%s" % (i + 1, s["cls"], s["opts"], s["hasCorrect"]))
        print("      %s" % s["head"])
    html = page.evaluate("""
    () => {
      const el = document.querySelector('.item-body');
      if (!el) return '未找到 .item-body';
      return 'ITEM-BODY:\\n' + el.outerHTML.slice(0, 1800);
    }
    """)
    print("-" * 64)
    print("首个题目块 HTML：")
    print(html)
    print("页面文本开头：")
    print((page.evaluate("() => (document.body.innerText || '').slice(0, 400)")))


def open_paper(page, paper, action=""):
    """点击指定试卷卡片，等待同标签页跳转到考试详情页，解析 courseId/examId。

    action 为空则停在详情页；action=view 点「查看试卷」进结果页；action=retry 点「再次答题」进答题页。
    """
    before = page.url
    page.get_by_text(paper, exact=False).first.click()
    try:
        page.wait_for_url(lambda u: u != before, timeout=10000)
    except Exception as exc:
        print("未检测到地址变化（%s）" % exc)
    page.wait_for_timeout(2000)
    print("-" * 64)
    print("落地地址：", page.url)
    m = re.search(r"/exam/(\d+)/(\d+)", page.url)
    if m:
        print("解析结果：courseId=%s examId=%s" % (m.group(1), m.group(2)))
    else:
        print("警告：地址不符合 /exam/<courseId>/<examId> 结构，需另找 ID 来源。")
    shot(page, "detail-" + paper)

    if action:
        btn = {"view": "查看试卷", "retry": "再次答题"}.get(action, action)
        target = page
        try:
            with page.context.expect_page(timeout=7000) as pop:
                page.get_by_text(btn, exact=True).first.click(timeout=6000)
            target = pop.value
            target.wait_for_load_state("domcontentloaded")
            target.wait_for_timeout(2500)
            print("「%s」在新标签页打开：%s" % (btn, target.url))
        except Exception:
            page.wait_for_timeout(2500)
            print("「%s」在当前页处理，地址：%s" % (btn, page.url))
        shot(target, action + "-" + paper)

        if action == "retry" and "/start" in target.url:
            try:
                target.get_by_text("开始", exact=True).last.click(timeout=6000)
                target.wait_for_timeout(3000)
                print("点「开始」后地址：", target.url)
            except Exception as exc:
                print("点「开始」失败：%s" % exc)
        dump_answer_or_result(target, action)


def goto_course(page, course):
    page.goto(INDEX, wait_until="domcontentloaded")
    page.wait_for_timeout(2500)
    dismiss_popups(page)
    try:
        page.get_by_text("我听的课", exact=False).first.click()
        page.wait_for_timeout(1800)
    except Exception as exc:
        print("点击「我听的课」失败：", exc)
    dismiss_popups(page)
    if course:
        try:
            page.get_by_text(course, exact=False).first.click()
            page.wait_for_timeout(2500)
        except Exception as exc:
            print("进入课程「%s」失败：%s" % (course, exc))
        dismiss_popups(page)


def step_login(page, wait):
    page.goto(INDEX, wait_until="domcontentloaded")
    page.wait_for_timeout(2500)
    dump_login(page)
    dump_qr_detail(page)
    shot(page, "login-before")
    if is_logged_in(page):
        return
    print("-" * 64)
    print("请在弹出的浏览器窗口里手动扫码登录（不要在此输入账号密码）。")
    deadline = time.time() + wait
    last = 0
    while time.time() < deadline:
        if is_logged_in(page):
            print("检测到登录成功。")
            break
        if refresh_if_expired(page):
            print("二维码已过期，已自动点「重新获取」。")
        if time.time() - last >= 10:
            last = time.time()
            print("等待扫码中，剩余约 %d 秒……" % int(deadline - time.time()))
        page.wait_for_timeout(1500)
    else:
        print("等待超时，仍未检测到登录。")
    dump_login(page)
    shot(page, "login-after")


def main():
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except Exception:
        pass
    ap = argparse.ArgumentParser(description="雨课堂选择器校准探针")
    ap.add_argument("step", choices=["login", "course", "papers", "logs", "open", "answer", "exam", "result"])
    ap.add_argument("--course", default="")
    ap.add_argument("--paper", default="")
    ap.add_argument("--action", default="", choices=["", "view", "retry"],
                    help="open 步骤的后续动作：view 查看试卷、retry 再次答题")
    ap.add_argument("--course-id", default="")
    ap.add_argument("--exam-id", default="")
    ap.add_argument("--wait", type=int, default=240)
    ap.add_argument("--hold", type=int, default=0)
    ap.add_argument("--channel", default=os.environ.get("AGENT_BROWSER_CHANNEL", "chrome"),
                    help="浏览器通道：chrome、msedge，留空用 Playwright 自带 Chromium")
    args = ap.parse_args()

    os.makedirs(PROFILE, exist_ok=True)
    with sync_playwright() as pw:
        ctx = pw.chromium.launch_persistent_context(
            PROFILE, headless=False, slow_mo=100,
            channel=args.channel or None,
            viewport={"width": 1440, "height": 900},
            args=["--disable-blink-features=AutomationControlled"],
        )
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
        page.set_default_timeout(15000)

        if args.step == "login":
            step_login(page, args.wait)
        elif args.step == "course":
            goto_course(page, args.course)
            dump_course(page, args.course)
            shot(page, "course")
        elif args.step == "papers":
            goto_course(page, args.course)
            dump_papers(page)
            shot(page, "papers")
        elif args.step == "logs":
            goto_course(page, args.course)
            dump_logs(page)
            shot(page, "logs")
        elif args.step == "open":
            if not args.paper:
                print("open 步骤需要 --paper")
                sys.exit(2)
            goto_course(page, args.course)
            open_paper(page, args.paper, args.action)
        elif args.step == "answer":
            if not args.exam_id:
                print("answer 步骤需要 --exam-id")
                sys.exit(2)
            page.goto(EXAM.format(e=args.exam_id), wait_until="domcontentloaded")
            page.wait_for_timeout(3500)
            dump_answer_or_result(page, "retry")
            shot(page, "answer")
        elif args.step == "exam":
            if not (args.course_id and args.exam_id):
                print("exam 步骤需要 --course-id 与 --exam-id")
                sys.exit(2)
            dump_exam(page, args.course_id, args.exam_id)
            shot(page, "exam")
        elif args.step == "result":
            if not args.exam_id:
                print("result 步骤需要 --exam-id")
                sys.exit(2)
            dump_result(page, args.exam_id)
            shot(page, "result")

        if args.hold > 0:
            print("-" * 64)
            print("保持浏览器打开 %d 秒，可手动操作后再观察。" % args.hold)
            page.wait_for_timeout(args.hold * 1000)
            shot(page, args.step + "-after-hold")

        ctx.close()
        print("已关闭浏览器。")


if __name__ == "__main__":
    main()
