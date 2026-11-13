#!/usr/bin/env python3
"""check_numbers.py —— 论文/README/SUMMARY 的数字与归档数据一致性校验

只做一件事：把"文档里写的数"与"归档里的数"逐项对齐，不一致就失败。
它不复算物理量，只复算**聚合**（median/min/max）与**派生百分比**——
这两步恰好是人工抄写最容易出错、又最容易验证的地方。

两个数据目录，含义不同：
  docs/validation/2026-11-13/kernel/repeat/        优化前（镜像 #19）
  docs/validation/2026-11-13/kernel/repeat-routeA/ 当前实现（镜像 #20，Route A）

被校验对象：
  两个目录的 SUMMARY.txt（必须等于各自 all.csv 的复算结果）
  report/paper/chapter6-test.tex（tab:cost / tab:routea / tab:counters / 正文）
  report/paper/abstract.tex、chapter8-conclusion.tex
  README.md、docs/validation/2026-11-13/SUMMARY.md

退出码：0 全部一致；1 有不一致。
"""
import csv
import io
import os
import re
import statistics as st
import sys

ROOT = __file__.rsplit("/scripts/", 1)[0]
BASE = ROOT + "/docs/validation/2026-11-13/kernel"
HIST = BASE + "/repeat"
MAIN = BASE + "/repeat-routeA"

errors = []
checks = 0


def need(haystack, needle, where):
    global checks
    checks += 1
    if needle not in haystack:
        errors.append("%s: 缺少 %r" % (where, needle))


def read(path):
    return io.open(path, encoding="utf-8").read()


def recompute(dir_path):
    rows = list(csv.DictReader(io.open(dir_path + "/all.csv", encoding="utf-8")))
    acc = {}
    for r in rows:
        if r["ratio_yat_over_cfs"]:
            acc.setdefault(r["tag"], []).append(float(r["ratio_yat_over_cfs"]))
    return {k: (st.median(v), min(v), max(v)) for k, v in acc.items() if len(v) >= 2}


def check_summary(dir_path, label):
    summary = read(dir_path + "/SUMMARY.txt")
    exp = recompute(dir_path)
    for tag, (med, lo, hi) in exp.items():
        row = [l for l in summary.splitlines() if l.startswith(tag)]
        if not row:
            errors.append("%s/SUMMARY.txt 缺少配置 %s" % (label, tag))
            continue
        got = tuple(float(x) for x in row[0].split()[2:5])
        for name, a, b in (("median", got[0], med), ("min", got[1], lo), ("max", got[2], hi)):
            global checks
            checks += 1
            if abs(a - b) > 5e-4:
                errors.append("%s/SUMMARY.txt %s %s: 表内 %.3f, 复算 %.3f"
                              % (label, tag, name, a, b))
    return exp


hist = check_summary(HIST, "repeat")
main = check_summary(MAIN, "repeat-routeA")
for d, name in ((main, "repeat-routeA"), (hist, "repeat")):
    if set(d) != {"none-sleep200", "shared-sleep200", "none-sleep0"}:
        errors.append("%s/all.csv 的配置集不符合预期: %s" % (name, sorted(d)))

# ---------------------------------------------------------------- 论文
tex6 = read(ROOT + "/report/paper/chapter6-test.tex")
tex_abs = read(ROOT + "/report/paper/abstract.tex")
tex8 = read(ROOT + "/report/paper/chapter8-conclusion.tex")
readme = read(ROOT + "/README.md")
summary_md = read(ROOT + "/docs/validation/2026-11-13/SUMMARY.md")
docs_all = tex6 + tex_abs + tex8

# tab:cost 三行 = 当前实现（MAIN）
cost = tex6[tex6.index(r"\label{tab:cost}"):tex6.index(r"\end{tabular}", tex6.index(r"\label{tab:cost}"))]
for tag, (med, lo, hi) in main.items():
    for v in (med, lo, hi):
        need(cost, "%.2f" % v, "chapter6 tab:cost")

# tab:routea：改前 / 改后中位数都要出现
for v in (hist["none-sleep200"][0], main["none-sleep200"][0]):
    need(tex6, "%.2f" % v, "chapter6 tab:routea")
need(tex6, "-9.5\\%", "chapter6 Route A 变化")

# ---------------------------------------------------------------- 计数器
log = read(MAIN + "/none-sleep200/run-1.log")
counters = {}
for line in log.splitlines():
    m = re.match(r"\s+([a-z_0-9]+)\s+(\d+)\s*$", line)
    if m:
        counters[m.group(1)] = int(m.group(2))

want = ["ttwu_ok", "select_calls", "select_sticky_idle", "select_decided",
        "jobs_placed", "place_hit", "place_pending", "enq_calls",
        "enq_cpus_allowed", "balance_runs", "balance_noop", "pick_calls"]
for k in want:
    checks += 1
    if k not in counters:
        errors.append("run-1.log 缺少计数器 %s" % k)
        continue
    need(tex6, str(counters[k]), "chapter6 计数器 %s" % k)

sel, ttwu = counters["select_calls"], counters["ttwu_ok"]
sticky, hit, pending = counters["select_sticky_idle"], counters["place_hit"], counters["place_pending"]
for txt in ("%.1f" % (100.0 * sel / ttwu),
            "%.1f" % (100.0 * sticky / sel),
            "%.1f" % (100.0 * hit / (hit + pending))):
    need(docs_all, txt + r"\%", "论文派生百分比 %s%%" % txt)

# ---------------------------------------------------------------- README
need(readme, "%.2f×" % main["none-sleep200"][0], "README 主口径")
need(readme, "[%.2f, %.2f]" % main["none-sleep200"][1:], "README 主口径全距")
need(readme, "%.2f×" % main["shared-sleep200"][0], "README shared")
need(readme, "%.2f×" % main["none-sleep0"][0], "README 无唤醒")
need(readme, "%d 次唤醒触发 %d 次选核" % (ttwu, sel), "README 计数器")
need(readme, "%d/%d = %.1f%%" % (hit, hit + pending, 100.0 * hit / (hit + pending)), "README 接管率")

# ---------------------------------------------------------------- SUMMARY.md
need(summary_md, "%.3f" % main["none-sleep200"][0], "SUMMARY.md 主口径中位数")
need(summary_md, "%.3f" % hist["none-sleep200"][0], "SUMMARY.md 优化前中位数")
need(summary_md, "%d" % counters["balance_runs"], "SUMMARY.md balance_runs")

if errors:
    print("❌ 数字一致性检查失败（%d 项）:" % len(errors))
    for e in errors:
        print("   - " + e)
    sys.exit(1)

print("✅ 数字一致性检查通过：%d 项比对" % checks)
print("   仿真器数据不在校验范围（前期工作，非证据链）")
for label, d in (("优化前 #19", hist), ("当前 #20", main)):
    print("   %-10s %-16s median %.3f  [%.3f, %.3f]"
          % (label, "none-sleep200", d["none-sleep200"][0], d["none-sleep200"][1], d["none-sleep200"][2]))