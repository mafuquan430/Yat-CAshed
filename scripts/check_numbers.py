#!/usr/bin/env python3
"""check_numbers.py —— 论文/README 的数字与归档数据一致性校验

只做一件事：把"论文里写的数"与"归档里的数"逐项对齐，不一致就失败。
它不复算物理量，只复算**聚合**（median/min/max）与**派生百分比**——
这两步恰好是人工抄写最容易出错、又最容易验证的地方。

数据来源（唯一事实）：
  docs/validation/2026-11-13/kernel/repeat/all.csv        每轮原始比值
  docs/validation/2026-11-13/kernel/repeat/none-sleep200/run-1.log  计数器

被校验对象：
  docs/validation/2026-11-13/kernel/repeat/SUMMARY.txt   汇总表
  report/paper/chapter6-test.tex, abstract.tex, chapter8-conclusion.tex
  README.md

退出码：0 全部一致；1 有不一致。
"""
import csv
import io
import re
import statistics as st
import sys

ROOT = __file__.rsplit("/scripts/", 1)[0]
REPEAT = ROOT + "/docs/validation/2026-11-13/kernel/repeat"

errors = []
checks = 0


def need(haystack, needle, where):
    global checks
    checks += 1
    if needle not in haystack:
        errors.append("%s: 缺少 %r" % (where, needle))


def read(path):
    return io.open(path, encoding="utf-8").read()


# ---------------------------------------------------------------- 1. 复算聚合
rows = list(csv.DictReader(io.open(REPEAT + "/all.csv", encoding="utf-8")))
by_tag = {}
for r in rows:
    if not r["ratio_yat_over_cfs"]:
        continue
    by_tag.setdefault(r["tag"], []).append(float(r["ratio_yat_over_cfs"]))

expected = {}
for tag, vals in by_tag.items():
    if len(vals) < 2:
        continue
    expected[tag] = (st.median(vals), min(vals), max(vals))

if set(expected) != {"none-sleep200", "shared-sleep200", "none-sleep0"}:
    errors.append("all.csv 的配置集不符合预期: %s" % sorted(expected))

# ---------------------------------------------------------------- 2. SUMMARY
summary = read(REPEAT + "/SUMMARY.txt")
for tag, (med, lo, hi) in expected.items():
    row = [l for l in summary.splitlines() if l.startswith(tag)]
    if not row:
        errors.append("SUMMARY.txt 缺少配置 %s" % tag)
        continue
    parts = row[0].split()
    got = tuple(float(x) for x in parts[2:5])
    for label, a, b in (("median", got[0], med), ("min", got[1], lo), ("max", got[2], hi)):
        checks += 1
        if abs(a - b) > 5e-4:
            errors.append("SUMMARY.txt %s %s: 表内 %.3f, 复算 %.3f" % (tag, label, a, b))

# ---------------------------------------------------------------- 3. 论文表格
tex6 = read(ROOT + "/report/paper/chapter6-test.tex")
tex_abs = read(ROOT + "/report/paper/abstract.tex")
tex8 = read(ROOT + "/report/paper/chapter8-conclusion.tex")
readme = read(ROOT + "/README.md")

cost_block = tex6[tex6.index(r"\label{tab:cost}"):tex6.index(r"\end{tabular}", tex6.index(r"\label{tab:cost}"))]
for tag, (med, lo, hi) in expected.items():
    for v in (med, lo, hi):
        need(cost_block, "%.2f" % v, "chapter6 tab:cost")

# 正文里的三个比值必须与表格一致
need(tex6, "中位数 %.2f" % expected["none-sleep200"][0], "chapter6 正文")
need(tex6, "%.2f，全距 $[%.2f,%.2f]$ 跨 1" % (expected["none-sleep0"][0],
     expected["none-sleep0"][1], expected["none-sleep0"][2]), "chapter6 正文")

# ---------------------------------------------------------------- 4. 计数器
log = read(REPEAT + "/none-sleep200/run-1.log")
counters = {}
for line in log.splitlines():
    m = re.match(r"\s+([a-z_0-9]+)\s+(\d+)\s*$", line)
    if m:
        counters[m.group(1)] = int(m.group(2))

want = ["ttwu_ok", "select_calls", "select_sticky_idle", "select_decided",
        "jobs_placed", "place_hit", "place_miss", "place_pending",
        "enq_calls", "enq_cpus_allowed", "balance_runs", "pick_calls"]
for k in want:
    checks += 1
    if k not in counters:
        errors.append("run-1.log 缺少计数器 %s" % k)
        continue
    need(tex6, str(counters[k]), "chapter6 计数器 %s" % k)

# 派生百分比：95.1% / 44.4% / 97.9%
sel = counters["select_calls"]
ttwu = counters["ttwu_ok"]
sticky = counters["select_sticky_idle"]
hit = counters["place_hit"]
pending = counters["place_pending"]
derived = {
    "回调进入率": (100.0 * sel / ttwu, "%.1f" % (100.0 * sel / ttwu)),
    "空闲复用率": (100.0 * sticky / sel, "%.1f" % (100.0 * sticky / sel)),
    "接管率": (100.0 * hit / (hit + pending), "%.1f" % (100.0 * hit / (hit + pending))),
}
for name, (_, txt) in derived.items():
    need(tex6 + tex_abs + tex8, txt + r"\%", "论文派生百分比 %s" % name)

# ---------------------------------------------------------------- 5. README
need(readme, "%.2f×" % expected["none-sleep200"][0], "README 主口径")
need(readme, "[%.2f, %.2f]" % expected["none-sleep200"][1:], "README 主口径全距")
need(readme, "%.2f×" % expected["shared-sleep200"][0], "README shared")
need(readme, "%.2f，全距 [%.2f, %.2f] 跨 1" % (expected["none-sleep0"][0],
     expected["none-sleep0"][1], expected["none-sleep0"][2]), "README 无唤醒")
need(readme, "%d 次唤醒触发 %d 次选核" % (ttwu, sel), "README 计数器")
need(readme, "%d/%d = %.1f%%" % (hit, hit + pending, 100.0 * hit / (hit + pending)), "README 接管率")

# ---------------------------------------------------------------- 报告
if errors:
    print("❌ 数字一致性检查失败（%d 项）:" % len(errors))
    for e in errors:
        print("   - " + e)
    sys.exit(1)

print("✅ 数字一致性检查通过：%d 项比对" % checks)
print("   仿真器数据不在校验范围（前期工作，非证据链）")
for tag in ("none-sleep200", "shared-sleep200", "none-sleep0"):
    med, lo, hi = expected[tag]
    print("   %-16s median %.3f  [%.3f, %.3f]" % (tag, med, lo, hi))