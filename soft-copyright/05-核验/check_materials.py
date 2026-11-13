#!/usr/bin/env python3
"""check_materials.py —— 软著材料的机械核验（提交前一键自检）

检查项（全部来自官方口径，见 README）：
  1. 源程序 PDF 页数 = 60（前 30 + 后 30），每页正文行数 ≥ 50；
  2. 每页页眉含软件全称与版本号，页码连续；
  3. 说明书 PDF 页数在 15–30 之间，页眉含软件全称；
  4. 三份材料中的软件全称与版本号逐字一致；
  5. 申请表“主要功能与技术特点”字数在 500–1300 之间。

用法：python3 check_materials.py
"""
import io
import os
import re
import subprocess
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOFTWARE = "Yat-CAShed 缓存感知调度软件"
VERSION = "V1.0"

OK, BAD = "  [通过]", "  [失败]"
failures = []


def pdf_pages(path):
    out = subprocess.run(["pdfinfo", path], capture_output=True, text=True).stdout
    m = re.search(r"^Pages:\s+(\d+)", out, re.M)
    return int(m.group(1)) if m else -1


def pdf_text(path):
    return subprocess.run(["pdftotext", "-layout", path, "-"], capture_output=True, text=True).stdout


def check(cond, msg):
    print((OK if cond else BAD) + " " + msg)
    if not cond:
        failures.append(msg)


def main():
    src = os.path.join(BASE, "02-源程序", "源程序鉴别材料.pdf")
    man = os.path.join(BASE, "03-说明书", "说明书.pdf")
    form = os.path.join(BASE, "01-申请表", "申请表字段与功能描述.md")

    print("== 1. 源程序鉴别材料 ==")
    check(os.path.isfile(src), "文件存在：02-源程序/源程序鉴别材料.pdf")
    if os.path.isfile(src):
        n = pdf_pages(src)
        check(n == 60, "页数 = 60（实测 %d）" % n)
        pages = [p for p in pdf_text(src).split("\f") if p.strip()]
        check(len(pages) == 60, "可解析页数 = 60（实测 %d）" % len(pages))
        lines_per_page = []
        headers_ok, numbers_ok = True, True
        for i, page in enumerate(pages, 1):
            body = [l for l in page.splitlines() if l.strip()]
            if not generic_header(body):
                headers_ok = False
            if not re.search(r"第\s*%d\s*页" % i, page):
                numbers_ok = False
            lines_per_page.append(len([l for l in body if not is_header_line(l)]))
        check(headers_ok, "每页页眉含「%s %s」" % (SOFTWARE, VERSION))
        check(numbers_ok, "页码连续（1..60）")
        low = [(i + 1, c) for i, c in enumerate(lines_per_page) if c < 50]
        check(not low, "每页正文行数 ≥ 50（不足的页：%s）" % (low or "无"))
        print("      正文行数：最少 %d，最多 %d，平均 %.1f"
              % (min(lines_per_page), max(lines_per_page),
                 sum(lines_per_page) / len(lines_per_page)))

    print("== 2. 软件说明书 ==")
    check(os.path.isfile(man), "文件存在：03-说明书/说明书.pdf")
    if os.path.isfile(man):
        n = pdf_pages(man)
        check(15 <= n <= 30, "页数在 15–30 之间（实测 %d）" % n)
        pages = [p for p in pdf_text(man).split("\f") if p.strip()]
        head_ok = all(SOFTWARE in p for p in pages[1:])
        check(head_ok, "正文每页页眉含软件全称")
        num_ok = all(re.search(r"第\s*\d+\s*页", p) for p in pages[1:])
        check(num_ok, "正文每页有页码")
        fig = len(re.findall(r"图\s*\d+", pdf_text(man)))
        print("      插图引用出现 %d 次，平均每页 %.1f 行" % (fig, sum(
            len([l for l in p.splitlines() if l.strip()]) for p in pages) / len(pages)))

    print("== 3. 全称与版本号一致性 ==")
    for rel in ("01-申请表/申请表字段与功能描述.md",
                "03-说明书/说明书.tex",
                "02-源程序/源程序鉴别材料.tex"):
        path = os.path.join(BASE, rel)
        if not os.path.isfile(path):
            check(False, "文件存在：%s" % rel)
            continue
        t = io.open(path, encoding="utf-8").read()
        check(SOFTWARE in t, "%s 含软件全称" % rel)
        check(VERSION in t, "%s 含版本号 %s" % (rel, VERSION))

    print("== 4. 功能描述字数 ==")
    if os.path.isfile(form):
        t = io.open(form, encoding="utf-8").read()
        try:
            sec = t.split("## 二、主要功能与技术特点")[1].split("## 三、")[0]
            body = sec.split("本软件是一套运行于")[1]
            body = ("本软件是一套运行于" + body).replace("\n", "").replace(" ", "")
        except IndexError:
            body = ""
        cjk = len(re.findall(r"[\u4e00-\u9fff]", body))
        check(500 <= len(body) <= 1300,
              "字符数在 500–1300（实测 %d，其中汉字 %d）" % (len(body), cjk))
    else:
        check(False, "文件存在：01-申请表/申请表字段与功能描述.md")

    print()
    if failures:
        print("结果：%d 项未通过" % len(failures))
        return 1
    print("结果：全部通过（机械项）。人工项见 TODO.md 第 3 节。")
    return 0


def is_header_line(line):
    return SOFTWARE in line or re.match(r"^第\s*\d+\s*页", line.strip())


def generic_header(body):
    head = " ".join(body[:2])
    return SOFTWARE in head and VERSION in head


if __name__ == "__main__":
    sys.exit(main())