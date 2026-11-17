#!/usr/bin/env python3
"""check_structure.py —— 仓库结构守卫（`make structure-check`）

它守的是"目录与文档的组织"，而不是代码本身：

  1. 顶层只允许出现约定的目录/文件（防止临时目录、实验残留混入）；
  2. 关键入口文件存在且非空；
  3. 每个顶层目录都在 README.md 里被提到（否则等于"没写进文档"）；
  4. docs/ 下每个 Markdown 都被文档索引链接（杜绝"写了没人找得到"）；
  5. 不含被跟踪的构建产物/编辑器残留（*.o、*.pyc、*~ 等）；
  6. 软著材料包的三份交付件齐全。

任何一项不满足即以非零码退出，供 `make verify-all` 串联。
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

TOP_DIRS = ["docs", "linux-6.8-main", "patches", "report", "scripts", "simulator", "soft-copyright"]
TOP_FILES = ["README.md", "CHANGELOG.md", "CONTRIBUTING.md", "LICENSE", "Makefile",
             ".gitignore", ".gitattributes", ".editorconfig"]
# 本地编辑器目录不入库，也不参与顶层布局判定
IGNORE_TOP = {".vscode", ".idea", ".venv", "__pycache__"}
REQUIRED = TOP_FILES + [
    "docs/README.md",
    "docs/ARCHITECTURE.md",
    "docs/DIRECTORY.md",
    "docs/DEVELOPMENT.md",
    "docs/OPERATIONS.md",
    "patches/series",
    "scripts/check_numbers.py",
    "scripts/check_structure.py",
    "soft-copyright/TODO.md",
]
SOFTCOPY_FILES = [
    "soft-copyright/README.md",
    "soft-copyright/TODO.md",
    "soft-copyright/01-申请表/申请表字段与功能描述.md",
    "soft-copyright/02-源程序/源程序鉴别材料.pdf",
    "soft-copyright/03-说明书/说明书.pdf",
    "soft-copyright/04-签章页/签章页-抄写说明.md",
    "soft-copyright/06-提交指引/官网填报步骤.md",
]
BAD_SUFFIXES = (".o", ".a", ".so", ".pyc", ".pyo", ".orig", ".rej", ".swp", ".tmp", ".class")
BAD_NAMES = ("~", ".DS_Store", ".Thumbs.db")

fail = 0


def report(ok, msg):
    global fail
    print(("  [通过] " if ok else "  [失败] ") + msg)
    if not ok:
        fail = 1


def tracked_files():
    out = subprocess.run(["git", "ls-files"], capture_output=True, text=True).stdout
    return [l for l in out.splitlines() if l]


def main():
    print("== 1. 顶层布局 ==")
    entries = sorted(e for e in os.listdir(".")
                     if not e.startswith(".git") and e not in IGNORE_TOP)
    unexpected = [e for e in entries if e not in TOP_DIRS and e not in TOP_FILES]
    report(not unexpected, "顶层无未登记条目（多余：%s）" % (unexpected or "无"))
    for d in TOP_DIRS:
        report(os.path.isdir(d), "目录存在：%s/" % d)

    print("== 2. 入口文件 ==")
    for f in REQUIRED:
        ok = os.path.isfile(f) and os.path.getsize(f) > 0
        report(ok, "%s（非空）" % f)

    print("== 3. 顶层目录已写入 README ==")
    readme = open("README.md", encoding="utf-8").read()
    for d in TOP_DIRS:
        report(("%s/" % d) in readme or ("`%s`" % d) in readme, "README.md 提到 %s/" % d)

    print("== 4. docs/ 索引完整性 ==")
    index = open("docs/README.md", encoding="utf-8").read()
    archive_index = ""
    if os.path.isfile("docs/archive/README.md"):
        archive_index = open("docs/archive/README.md", encoding="utf-8").read()
    missing = []
    for name in sorted(os.listdir("docs")):
        if not name.endswith(".md") or name == "README.md":
            continue
        if "](" + name + ")" not in index and "](" + name + ")" not in archive_index:
            missing.append("docs/" + name)
    for name in sorted(os.listdir("docs/archive")):
        if not name.endswith(".md") or name == "README.md":
            continue
        if "](" + name + ")" not in index and "](" + name + ")" not in archive_index:
            missing.append("docs/archive/" + name)
    report(not missing, "docs/ 下每个 Markdown 都被索引（未索引：%s）" % (missing or "无"))

    print("== 5. 无被跟踪的构建产物 ==")
    bad = []
    for p in tracked_files():
        if p.startswith("linux-6.8-main/"):
            continue  # 上游内核树自带工具产物，不在此守卫范围
        if p.endswith(BAD_SUFFIXES) or p.endswith(BAD_NAMES):
            bad.append(p)
    report(not bad, "被跟踪文件里无构建产物/残留（命中：%s）" % (bad[:5] or "无"))

    print("== 6. 软著材料齐全 ==")
    for f in SOFTCOPY_FILES:
        ok = os.path.isfile(f) and os.path.getsize(f) > 1024
        report(ok, f)

    print()
    if fail:
        print("结构守卫：未通过（见上）")
        return 1
    print("结构守卫：通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())