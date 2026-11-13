#!/usr/bin/env python3
"""汇总 AJLR-lite 矩阵实验，并生成论文用的表与图。

用法：
    python3 scripts/analyze_ajlr_lite.py                # 读 docs/validation/2026-11-13/matrix
    python3 scripts/analyze_ajlr_lite.py --figures      # 额外生成 report/paper/img/*.png

统计口径与论文一致：以"每个种子的配对均值"为独立观测（n=5），
用样本标准差与 t(0.975,4)=2.776 计算 95% 区间；不以逐个用例为独立样本。
"""
import argparse
import csv
import glob
import json
import math
import os

# t(0.975, df)：键是**自由度**（= n-1），不是样本量。
# 写成 {5: 2.776} 会让 n=5 的区间窄 7.4% —— 这类"表查错一行"的错误不会报错，
# 只会让结论显得更显著，所以下标在这里显式写成 df。
T = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365,
     8: 2.306, 9: 2.262, 10: 2.228, 11: 2.201, 12: 2.179}

LEVELS = [(1, 20, 0.6), (21, 40, 0.8), (41, 60, 1.0), (61, 80, 1.2), (81, 100, 1.5)]
ALGS = ["WFD", "CacheAware_v2", "CacheAware_v3"]


def load_pairs(path):
    rows = {}
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            rows.setdefault(r["Algorithm"], {})[int(r["TestCase"])] = r
    base = rows["WFD"]
    out = []
    for case in sorted(base):
        rec = {"case": case, "wfd": base[case]}
        for alg in ALGS[1:]:
            if alg in rows and case in rows[alg]:
                rec[alg] = rows[alg][case]
        out.append(rec)
    return out


def mean(xs):
    return sum(xs) / len(xs)


def sd(xs):
    m = mean(xs)
    return math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1)) if len(xs) > 1 else 0.0


def ci95(xs):
    if len(xs) < 2:
        return float("nan")
    return T.get(len(xs) - 1, 1.96) * sd(xs) / math.sqrt(len(xs))


def pct_improve(base, cand):
    b = float(base["Makespan"])
    return (b - float(cand["Makespan"])) / b * 100.0 if b > 0 else 0.0


def summarize(cfg_dir):
    files = sorted(glob.glob(os.path.join(cfg_dir, "ajlr_lite_seed_*.csv")),
                   key=lambda p: int(p.split("_")[-1].split(".")[0]))
    if not files:
        return None
    per_seed = {alg: {"imp": [], "hit": [], "resp": []} for alg in ALGS[1:]}
    cases = []
    for f in files:
        pairs = load_pairs(f)
        cases.extend(pairs)
        for alg in ALGS[1:]:
            imps, hits, resps = [], [], []
            for rec in pairs:
                cand = rec.get(alg)
                if cand is None:
                    continue
                imps.append(pct_improve(rec["wfd"], cand))
                hits.append((float(cand["CacheHit"]) - float(rec["wfd"]["CacheHit"])) * 100.0)
                wresp = float(rec["wfd"]["ResponseTime"])
                resps.append((float(cand["ResponseTime"]) - wresp) / wresp * 100.0 if wresp else 0.0)
            per_seed[alg]["imp"].append(mean(imps))
            per_seed[alg]["hit"].append(mean(hits))
            per_seed[alg]["resp"].append(mean(resps))
    res = {"seeds": len(files), "cases": len(cases)}
    for alg in ALGS[1:]:
        for metric in ("imp", "hit", "resp"):
            xs = per_seed[alg][metric]
            res[f"{alg}.{metric}"] = {
                "mean": mean(xs), "sd": sd(xs), "ci": ci95(xs),
                "lo": mean(xs) - ci95(xs), "hi": mean(xs) + ci95(xs),
                "samples": [round(v, 4) for v in xs],
            }
    return res


def per_utilization(cfg_dir, alg="CacheAware_v3"):
    files = sorted(glob.glob(os.path.join(cfg_dir, "ajlr_lite_seed_*.csv")),
                   key=lambda p: int(p.split("_")[-1].split(".")[0]))
    pool = {}
    for f in files:
        for rec in load_pairs(f):
            cand = rec.get(alg)
            if cand is None:
                continue
            for lo, hi, u in LEVELS:
                if lo <= rec["case"] <= hi:
                    pool.setdefault(u, []).append(pct_improve(rec["wfd"], cand))
    return {u: {"n": len(v), "mean": mean(v), "ci": ci95(v)} for u, v in sorted(pool.items())}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="docs/validation/2026-11-13/matrix")
    ap.add_argument("--figures", action="store_true")
    ap.add_argument("--json")
    args = ap.parse_args()

    summary = {}
    print(f"{'config':<16}{'v2 imp%':>10}{'v3 imp%':>10}{'v3 CI':>10}"
          f"{'v3 hit pp':>11}{'v3 resp%':>10}")
    print("-" * 70)
    for cfg in sorted(os.listdir(args.root)):
        d = os.path.join(args.root, cfg)
        if not os.path.isdir(d):
            continue
        s = summarize(d)
        if not s:
            continue
        summary[cfg] = s
        v2, v3 = s["CacheAware_v2.imp"], s["CacheAware_v3.imp"]
        hit = s["CacheAware_v3.hit"]
        resp = s["CacheAware_v3.resp"]
        print(f"{cfg:<16}{v2['mean']:>10.3f}{v3['mean']:>10.3f}"
              f"{v3['ci']:>10.3f}{hit['mean']:>11.3f}{resp['mean']:>10.3f}")

    if args.json:
        with open(args.json, "w") as f:
            json.dump(summary, f, indent=2, ensure_ascii=False)

    if args.figures:
        make_figures(summary)


def make_figures(summary):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    out = os.path.join("report", "paper", "img")
    os.makedirs(out, exist_ok=True)

    # 图 1：命中率提升 vs Makespan 改进（负载窗口 alpha 的 Pareto 视角）
    fig, ax = plt.subplots(figsize=(6.2, 4.0))
    order = ["min-finish", "hr-a025", "hr-a050", "hr-a100", "hr-a200"]
    labels = {"min-finish": r"$\alpha=0$ (min-finish)",
              "hr-a025": r"$\alpha=0.25$", "hr-a050": r"$\alpha=0.5$",
              "hr-a100": r"$\alpha=1.0$", "hr-a200": r"$\alpha=2.0$"}
    for cfg in order:
        if cfg not in summary:
            continue
        s = summary[cfg]
        ax.errorbar(s["CacheAware_v3.hit"]["mean"], s["CacheAware_v3.imp"]["mean"],
                    yerr=s["CacheAware_v3.imp"]["ci"], xerr=s["CacheAware_v3.hit"]["ci"],
                    fmt="o", capsize=3, label="v3 " + labels[cfg])
    if "legacy" in summary:
        s = summary["legacy"]
        ax.plot(s["CacheAware_v2.hit"]["mean"], s["CacheAware_v2.imp"]["mean"],
                "s", color="tab:red", label="v2 (main-exp baseline)")
        ax.plot(s["CacheAware_v3.hit"]["mean"], s["CacheAware_v3.imp"]["mean"],
                "D", color="tab:red", alpha=0.55, label=r"v3 $\alpha=1.0$ (main-exp baseline)")
    if "hr-a050" in summary:
        s = summary["hr-a050"]
        ax.plot(s["CacheAware_v2.hit"]["mean"], s["CacheAware_v2.imp"]["mean"],
                "^", color="black", label="v2 (reset baseline)")
    ax.axhline(0, color="gray", lw=0.8, ls="--")
    ax.set_xlabel("cache-hit delta vs WFD (percentage points)")
    ax.set_ylabel("makespan improvement vs WFD (%)")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=7, loc="lower right")
    fig.tight_layout()
    p1 = os.path.join(out, "sim-pareto.png")
    fig.savefig(p1, dpi=200)
    print("wrote", p1)

    # 图 2：分利用率水平（同一干净基线下的 v2 vs v3 alpha=0.5）
    fig, axes = plt.subplots(1, 2, figsize=(7.2, 3.4))
    width = 0.36
    us = [u for _, _, u in LEVELS]
    d = os.path.join("docs/validation/2026-11-13/matrix", "hr-a050")
    for i, (alg, color, name) in enumerate([("CacheAware_v2", "tab:orange", "v2"),
                                            ("CacheAware_v3", "tab:blue", r"v3 $\alpha=0.5$")]):
        pu = per_utilization(d, alg)
        xs = [j + (i - 0.5) * width for j in range(len(us))]
        ys = [pu[u]["mean"] if u in pu else 0.0 for u in us]
        es = [pu[u]["ci"] if u in pu else 0.0 for u in us]
        axes[0].bar(xs, ys, width, yerr=es, capsize=3, color=color, label=name)
    axes[0].axhline(0, color="gray", lw=0.8)
    axes[0].set_xticks(range(len(us)))
    axes[0].set_xticklabels([str(u) for u in us])
    axes[0].set_xlabel("system utilization level")
    axes[0].set_ylabel("makespan improvement vs WFD (%)")
    axes[0].grid(alpha=0.3, axis="y")
    axes[0].legend(fontsize=8)
    axes[0].set_title("makespan", fontsize=9)

    for i, (alg, color, name) in enumerate([("CacheAware_v2", "tab:orange", "v2"),
                                            ("CacheAware_v3", "tab:blue", r"v3 $\alpha=0.5$")]):
        rows = {}
        files = sorted(glob.glob(os.path.join(d, "ajlr_lite_seed_*.csv")),
                       key=lambda p: int(p.split("_")[-1].split(".")[0]))
        for f in files:
            for rec in load_pairs(f):
                cand = rec.get(alg)
                if cand is None:
                    continue
                for lo, hi, u in LEVELS:
                    if lo <= rec["case"] <= hi:
                        rows.setdefault(u, []).append(
                            (float(cand["CacheHit"]) - float(rec["wfd"]["CacheHit"])) * 100.0)
        xs = [j + (i - 0.5) * width for j in range(len(us))]
        ys = [mean(rows[u]) if u in rows else 0.0 for u in us]
        es = [ci95(rows[u]) if u in rows else 0.0 for u in us]
        axes[1].bar(xs, ys, width, yerr=es, capsize=3, color=color, label=name)
    axes[1].axhline(0, color="gray", lw=0.8)
    axes[1].set_xticks(range(len(us)))
    axes[1].set_xticklabels([str(u) for u in us])
    axes[1].set_xlabel("system utilization level")
    axes[1].set_ylabel("cache-hit delta (pp)")
    axes[1].grid(alpha=0.3, axis="y")
    axes[1].set_title("cache hit", fontsize=9)

    fig.tight_layout()
    p2 = os.path.join(out, "sim-per-util.png")
    fig.savefig(p2, dpi=200)
    print("wrote", p2)


if __name__ == "__main__":
    main()