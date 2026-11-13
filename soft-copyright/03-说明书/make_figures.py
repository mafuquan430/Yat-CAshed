#!/usr/bin/env python3
"""make_figures.py —— 用归档的真实数据生成说明书插图

数据来源（不加工、不拟合）：
  docs/validation/2026-11-13/kernel/repeat/SUMMARY.txt         优化前 #19
  docs/validation/2026-11-13/kernel/repeat-routeA/SUMMARY.txt  当前   #20
  docs/validation/2026-11-13/kernel/repeat-routeA/all.csv      逐次运行
"""
import io
import os
import statistics as st

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch

ROOT = "/root/LAB_ungraduate/Yat-CAshed"
OUT = os.path.join(ROOT, "soft-copyright", "03-说明书", "figures")

# 中文字体
for cand in ("/usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc",
             "/usr/share/fonts/truetype/arphic/uming.ttc"):
    if os.path.isfile(cand):
        font_manager.fontManager.addfont(cand)
        plt.rcParams["font.family"] = font_manager.FontProperties(fname=cand).get_name()
        break
plt.rcParams["axes.unicode_minus"] = False


def summary(path):
    out = {}
    for line in io.open(path, encoding="utf-8").read().splitlines()[1:]:
        p = line.split()
        if len(p) >= 5:
            out[p[0]] = (float(p[2]), float(p[3]), float(p[4]))
    return out


def ratios(path, tag):
    import csv
    rows = list(csv.DictReader(io.open(path, encoding="utf-8")))
    return [float(r["ratio_yat_over_cfs"]) for r in rows
            if r["tag"] == tag and r["ratio_yat_over_cfs"]]


before = summary(ROOT + "/docs/validation/2026-11-13/kernel/repeat/SUMMARY.txt")
after = summary(ROOT + "/docs/validation/2026-11-13/kernel/repeat-routeA/SUMMARY.txt")
CSV = ROOT + "/docs/validation/2026-11-13/kernel/repeat-routeA/all.csv"

LABELS = {"none-sleep200": "主口径\n(可跨核+唤醒)", "shared-sleep200": "单核 pin\n(回调不进入)",
          "none-sleep0": "无唤醒\n(仅 WF_FORK)"}
ORDER = ["none-sleep200", "shared-sleep200", "none-sleep0"]

# ---------- 图 1：三配置墙钟比（当前实现），带 min/max
fig, ax = plt.subplots(figsize=(7.2, 3.6), dpi=200)
med = [after[k][0] for k in ORDER]
lo = [after[k][0] - after[k][1] for k in ORDER]
hi = [after[k][2] - after[k][0] for k in ORDER]
bars = ax.bar([LABELS[k] for k in ORDER], med, yerr=[lo, hi], capsize=6,
              color=["#c0392b", "#e67e22", "#27ae60"], width=0.55,
              error_kw=dict(ecolor="#333333", lw=1.1))
ax.axhline(1.0, color="#333333", ls="--", lw=1.0)
ax.text(2.42, 1.03, "CFS 基线", fontsize=9, color="#333333")
for b, v in zip(bars, med):
    ax.text(b.get_x() + b.get_width() / 2, v + 0.06, "%.2f×" % v,
            ha="center", fontsize=11, fontweight="bold")
ax.set_ylabel("墙钟比 ρ = Yat / CFS", fontsize=10)
ax.set_ylim(0, 2.45)
ax.set_title("图 1  三组配置的墙钟比值（每配置 5 次，柱=中位数，须=全距）", fontsize=10)
ax.spines["top"].set_visible(False)
ax.spines["right"].set_visible(False)
fig.tight_layout()
fig.savefig(OUT + "/fig1-three-configs.png")
plt.close(fig)

# ---------- 图 2：Route A 前后对比
fig, ax = plt.subplots(figsize=(7.2, 3.6), dpi=200)
x = range(len(ORDER))
w = 0.36
ax.bar([i - w / 2 for i in x], [before[k][0] for k in ORDER], w,
       label="优化前 #19", color="#95a5a6")
ax.bar([i + w / 2 for i in x], [after[k][0] for k in ORDER], w,
       label="优化后 #20", color="#2980b9")
for i, k in enumerate(ORDER):
    ax.text(i - w / 2, before[k][0] + 0.04, "%.2f" % before[k][0], ha="center", fontsize=9)
    ax.text(i + w / 2, after[k][0] + 0.04, "%.2f" % after[k][0], ha="center", fontsize=9)
ax.set_xticks(list(x))
ax.set_xticklabels([LABELS[k] for k in ORDER], fontsize=9)
ax.axhline(1.0, color="#333333", ls="--", lw=1.0)
ax.set_ylabel("墙钟比 ρ", fontsize=10)
ax.set_ylim(0, 2.45)
ax.legend(fontsize=9, frameon=False)
ax.set_title("图 2  就绪代际门禁（V1.0 新增）前后对比", fontsize=10)
ax.spines["top"].set_visible(False)
ax.spines["right"].set_visible(False)
fig.tight_layout()
fig.savefig(OUT + "/fig2-route-a.png")
plt.close(fig)

# ---------- 图 3：balance 调用结构（前后）
fig, ax = plt.subplots(figsize=(7.2, 2.9), dpi=200)
rows = [("优化前 #19", 757, 0), ("优化后 #20", 9, 621)]
for i, (name, runs, noop) in enumerate(rows):
    ax.barh(i, runs, color="#2980b9", height=0.42, label="balance_runs（取全局锁）" if i == 0 else None)
    ax.barh(i, noop, left=runs, color="#f1c40f", height=0.42,
            label="balance_noop（不取锁）" if i == 0 else None)
    ax.text(runs + noop + 14, i, "共 %d 次调用" % (runs + noop), va="center", fontsize=9)
    ax.text(runs / 2, i, str(runs), ha="center", va="center", fontsize=9, color="white", fontweight="bold")
    if noop:
        ax.text(runs + noop / 2, i, str(noop), ha="center", va="center", fontsize=9)
ax.set_yticks([0, 1])
ax.set_yticklabels([r[0] for r in rows], fontsize=10)
ax.set_xlabel("balance 钩子调用次数", fontsize=10)
ax.legend(fontsize=8.5, frameon=False, loc="lower right")
ax.set_title("图 3  balance 调用结构：757 次全量扫描 → 9 次扫描 + 621 次空转快路径", fontsize=10)
ax.spines["top"].set_visible(False)
ax.spines["right"].set_visible(False)
fig.tight_layout()
fig.savefig(OUT + "/fig3-balance.png")
plt.close(fig)

# ---------- 图 4：体系结构框图
fig, ax = plt.subplots(figsize=(7.4, 4.4), dpi=200)
ax.set_xlim(0, 10)
ax.set_ylim(0, 7)
ax.axis("off")

def box(x, y, w, h, text, color):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.06,rounding_size=0.12",
                                fc=color, ec="#34495e", lw=1.0))
    ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=8.6, wrap=True)

box(0.3, 5.6, 4.3, 1.0, "用户态：yat_bench\nDAG 负载 / 唤醒制造", "#d6eaf8")
box(5.4, 5.6, 4.3, 1.0, "用户态：运维脚本\ncompare_cfs_vs_yat.sh / run_kernel_repeats.sh", "#d6eaf8")
box(0.3, 3.9, 4.3, 1.2, "QEMU guest（4 vCPU, TCG）\ninitramfs + init 脚本", "#e8f8f5")
box(5.4, 3.9, 4.3, 1.2, "观测面\ndebugfs 31 项计数器 + 3 个 tracepoint", "#fef9e7")
box(0.3, 1.9, 9.4, 1.5, "内核调度类 yat_casched（SCHED_YAT_CASCHED = 8）\n"
    "select_task_rq · enqueue_task/dequeue_task · balance · task_dead · pick_next_task", "#fdebd0")
box(0.3, 0.3, 4.3, 1.2, "算法：CRP 重用距离\n+ MSF / LCIF 打分", "#fadbd8")
box(5.4, 0.3, 4.3, 1.2, "数据结构：每核历史表 / 加速表\n就绪池 + 代际号 / 批量上限", "#fadbd8")
for (x1, y1, x2, y2) in [(2.45, 5.55, 2.45, 5.15), (7.55, 5.55, 7.55, 5.15),
                         (2.45, 3.85, 2.45, 3.45), (7.55, 3.85, 7.55, 3.45),
                         (2.45, 1.85, 2.45, 1.55), (7.55, 1.85, 7.55, 1.55)]:
    ax.add_patch(FancyArrowPatch((x1, y1), (x2, y2), arrowstyle="<->", mutation_scale=11,
                                 color="#34495e", lw=0.9))
ax.text(5.0, 6.85, "图 4  软件组成与数据流", ha="center", fontsize=10)
fig.tight_layout()
fig.savefig(OUT + "/fig4-arch.png")
plt.close(fig)

print("figures written:")
for f in sorted(os.listdir(OUT)):
    print("  ", f, os.path.getsize(os.path.join(OUT, f)))