#!/usr/bin/env python3
"""gen_source_pdf.py —— 生成软著《源程序鉴别材料》PDF

规则（中国版权保护中心，2026 口径）：
  * 程序 ≥60 页：交前 30 页 + 后 30 页，共 60 页，连续页码；
  * 每页有效代码 ≥50 行（本项目取 55 行/页，留出安全余量）；
  * 页眉写「软件全称 + 版本号」，与申请表逐字一致；
  * A4 纵向单面。

本脚本只做机械劳动：去空行 → 按显示宽度折行 → 每 55 行切一页 → 生成 LaTeX → 编译。
不改写、不生成任何代码内容。
"""
import io
import os
import re
import subprocess
import sys

ROOT = "/root/LAB_ungraduate/Yat-CAshed"
OUT = os.path.join(ROOT, "soft-copyright", "02-源程序")
SOFTWARE = "Yat-CAShed 缓存感知调度软件"
VERSION = "V1.0"
LINES_PER_PAGE = 55
PAGES_EACH_SIDE = 30
WIDTH = 90  # 显示宽度（CJK 记 2 列）

# 程序顺序：头文件 → 调度类实现 → 用户态测试程序 → 运维脚本 → 门禁脚本
ORDER = [
    "linux-6.8-main/include/linux/sched/yat_casched.h",
    "linux-6.8-main/include/trace/events/yat_casched.h",
    "linux-6.8-main/kernel/sched/yat_casched.c",
    "linux-6.8-main/boot_test_scripts/yat_bench.c",
    "linux-6.8-main/boot_test_scripts/yat_simple_test.c",
    "linux-6.8-main/boot_test_scripts/yat_affinity_test.c",
    "linux-6.8-main/yat_test_scripts/compare_cfs_vs_yat.sh",
    "linux-6.8-main/yat_test_scripts/create_test_initramfs.sh",
    "scripts/run_kernel_repeats.sh",
    "scripts/check_numbers.py",
    "scripts/check_names.py",
    "scripts/check_docs.py",
]


def disp_width(s):
    return sum(2 if ord(c) > 0x2E7F else 1 for c in s)


def wrap(line, width=WIDTH):
    """按显示宽度折行，续行以 4 空格缩进标记（不改变语义，仅排版）。"""
    if disp_width(line) <= width:
        return [line]
    out, cur, cur_w = [], "", 0
    for ch in line:
        w = 2 if ord(ch) > 0x2E7F else 1
        if cur_w + w > width:
            out.append(cur)
            cur, cur_w = "    ", 4
        cur += ch
        cur_w += w
    out.append(cur)
    return out


def collect():
    lines = []
    manifest = []
    for rel in ORDER:
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            sys.exit("缺少文件：%s" % rel)
        raw = io.open(path, encoding="utf-8").read().replace("\r\n", "\n").split("\n")
        kept = 0
        lines.append("/* ==================== 文件：%s ==================== */" % rel)
        kept += 1
        for ln in raw:
            ln = ln.rstrip()
            if not ln.strip():
                continue
            for piece in wrap(ln):
                lines.append(piece)
                kept += 1
        manifest.append((rel, kept))
    return lines, manifest


def tex_escape(s):
    # verbatim 里不需要转义；这里只处理页眉（普通文本）
    return s


def build_pdf(lines, manifest):
    total = len(lines)
    need = LINES_PER_PAGE * PAGES_EACH_SIDE * 2
    if total < need:
        sys.exit("程序有效行数不足：%d < %d（60 页 × 55 行）" % (total, need))

    front = lines[:LINES_PER_PAGE * PAGES_EACH_SIDE]
    back = lines[-LINES_PER_PAGE * PAGES_EACH_SIDE:]
    pages = [front[i:i + LINES_PER_PAGE] for i in range(0, len(front), LINES_PER_PAGE)]
    pages += [back[i:i + LINES_PER_PAGE] for i in range(0, len(back), LINES_PER_PAGE)]
    assert len(pages) == 60, len(pages)
    assert all(len(p) == LINES_PER_PAGE for p in pages)

    body = []
    for idx, page in enumerate(pages):
        body.append("\\begin{yatpage}")
        body.append("\\begin{Verbatim}[fontsize=\\fontsize{8.2}{9.8}\\selectfont]")
        body.extend(page)
        body.append("\\end{Verbatim}")
        body.append("\\end{yatpage}")
        if idx != len(pages) - 1:
            body.append("\\newpage")
    body = "\n".join(body)

    head = r"""\documentclass[10pt,a4paper]{article}
\usepackage[UTF8,fontset=fandol]{ctex}
\usepackage[a4paper,left=1.6cm,right=1.6cm,top=1.4cm,bottom=1.2cm,headheight=12pt,headsep=6pt,footskip=10pt]{geometry}
\usepackage{fancyvrb}
\usepackage{fancyhdr}
\usepackage{lastpage}
\pagestyle{fancy}
\fancyhf{}
\fancyhead[L]{\small \texttt{__SOFTWARE__} \ \texttt{__VERSION__}}
\fancyhead[R]{\small 第 \thepage\ 页 / 共 \pageref{LastPage} 页}
\renewcommand{\headrulewidth}{0.4pt}
\fancyfoot[C]{}
\setlength{\parindent}{0pt}
\setlength{\topskip}{0pt}
\newenvironment{yatpage}{\vspace*{0pt}}{\par}
\begin{document}
\pagestyle{fancy}
__BODY__
\end{document}
"""
    head = head.replace("__SOFTWARE__", SOFTWARE).replace("__VERSION__", VERSION).replace("__BODY__", body)

    tex = os.path.join(OUT, "源程序鉴别材料.tex")
    io.open(tex, "w", encoding="utf-8", newline="\n").write(head)
    r = subprocess.run(["latexmk", "-xelatex", "-interaction=nonstopmode", "-halt-on-error",
                        os.path.basename(tex)], cwd=OUT, capture_output=True, text=True)
    if r.returncode != 0:
        tail = "\n".join((r.stdout + r.stderr).splitlines()[-25:])
        sys.exit("编译失败：\n" + tail)

    print("程序有效行数：%d（60 页 × %d 行 = %d）" % (total, LINES_PER_PAGE, need))
    print("源文件贡献行数：")
    for rel, n in manifest:
        print("  %-58s %5d" % (rel, n))
    print("输出：%s/源程序鉴别材料.pdf" % OUT)


if __name__ == "__main__":
    lines, manifest = collect()
    build_pdf(lines, manifest)