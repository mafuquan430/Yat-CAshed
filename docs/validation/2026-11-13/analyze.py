#!/usr/bin/env python3
"""Independent re-analysis of simulator CSVs (no project code reused)."""
import csv, glob, math, os
from collections import defaultdict

SIM = "/root/LAB_ungraduate/Yat-CAshed/simulator/result"
ARCH = "/root/LAB_ungraduate/Yat-CAshed/docs/validation/2026-10-24"

def load(path):
    rows = []
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            rows.append({k: (v if k == "Algorithm" else float(v)) for k, v in r.items()})
    return rows

def paired(rows):
    wfd, ca = {}, {}
    for r in rows:
        (wfd if r["Algorithm"] == "WFD" else ca)[int(r["TestCase"])] = r
    keys = sorted(set(wfd) & set(ca))
    out = []
    for k in keys:
        w, c = wfd[k], ca[k]
        imp = (w["Makespan"] - c["Makespan"]) / w["Makespan"] * 100 if w["Makespan"] else 0.0
        out.append(dict(case=k, w=w, c=c, imp=imp))
    return out

def mean(xs): return sum(xs) / len(xs)

def tcrit(n):
    T = {1:12.706,2:4.303,3:3.182,4:2.776,5:2.571,6:2.447,7:2.365,8:2.306,9:2.262,
         10:2.228,11:2.201,12:2.179,13:2.160,14:2.145,15:2.131,16:2.120,17:2.110,
         18:2.101,19:2.093,20:2.086,25:2.060,30:2.042}
    return T.get(n-1, 1.96)

def ci95(xs):
    n = len(xs); m = mean(xs)
    if n < 2: return m, float("nan"), float("nan")
    sd = math.sqrt(sum((x-m)**2 for x in xs)/(n-1))
    return m, sd, tcrit(n)*sd/math.sqrt(n)

def summarize(tag, files):
    seed_means, seed_hits, seed_wins, seed_resp = [], [], [], []
    allcases = []
    for f in files:
        p = paired(load(f))
        allcases += p
        seed_means.append(mean([x["imp"] for x in p]))
        seed_hits.append(mean([x["c"]["CacheHit"]-x["w"]["CacheHit"] for x in p])*100)
        seed_wins.append(sum(1 for x in p if x["c"]["Makespan"] < x["w"]["Makespan"])/len(p)*100)
        seed_resp.append(mean([(x["c"]["ResponseTime"]-x["w"]["ResponseTime"])/x["w"]["ResponseTime"]*100 for x in p]))
    print(f"--- {tag}: {len(files)} seeds, {len(allcases)} paired cases")
    for name, xs in (("makespan_imp_pct", seed_means), ("hit_delta_pp", seed_hits),
                     ("win_rate_pct", seed_wins), ("resp_delta_pct", seed_resp)):
        m, sd, h = ci95(xs)
        print(f"  {name:18s} mean={m:8.4f} sd={sd:7.4f} ci95=+-{h:6.4f} -> [{m-h:8.4f},{m+h:8.4f}] n={len(xs)}")
    print("  per-seed:", " ".join(f"{v:.4f}" for v in seed_means))
    imps = sorted(x["imp"] for x in allcases)
    n = len(imps)
    med = imps[n//2] if n % 2 else (imps[n//2-1]+imps[n//2])/2
    print(f"  per-case: mean={mean(imps):.3f} med={med:.3f} min={min(imps):.3f} max={max(imps):.3f} "
          f"abs>5%={sum(1 for v in imps if abs(v)>5)} abs>10%={sum(1 for v in imps if abs(v)>10)}")
    return seed_means, allcases

fresh = sorted(glob.glob(os.path.join(SIM, "algorithm_comparison_seed_*.csv")),
               key=lambda p: int(p.split("_")[-1].split(".")[0]))
summarize("fresh run (this audit)", fresh)

arch = sorted(glob.glob(os.path.join(ARCH, "seed-*", "algorithm_comparison_results.csv")))
if arch:
    summarize("archived 2026-10-24", arch)
    for f, a in zip(fresh, arch):
        print(f"  byte-identical {os.path.basename(os.path.dirname(a))}: "
              f"{open(f,'rb').read() == open(a,'rb').read()}")

levels = [(1,20,0.6),(21,40,0.8),(41,60,1.0),(61,80,1.2),(81,100,1.5)]
print("\n--- per-utilization per seed (improve%/wins)")
for i, f in enumerate(fresh, start=1):
    p = paired(load(f))
    cells = []
    for lo, hi, u in levels:
        sel = [x for x in p if lo <= x["case"] <= hi]
        cells.append(f"{u}:{mean([x['imp'] for x in sel]):+.2f}%/{sum(1 for x in sel if x['c']['Makespan']<x['w']['Makespan'])}")
    print(f"  seed{i}: " + "  ".join(cells))

print("\n--- pooled per-utilization (all seeds)")
pooled = defaultdict(list)
for f in fresh:
    for x in paired(load(f)):
        for lo, hi, u in levels:
            if lo <= x["case"] <= hi:
                pooled[u].append(x)
for lo, hi, u in levels:
    xs = pooled[u]
    imps = [x["imp"] for x in xs]
    m, sd, h = ci95(imps)
    hit = mean([x["c"]["CacheHit"]-x["w"]["CacheHit"] for x in xs])*100
    print(f"  u={u}: n={len(xs)} mean={m:+.3f}% ci95=+-{h:.3f} "
          f"WFD_ms={mean([x['w']['Makespan'] for x in xs]):.0f} CA_ms={mean([x['c']['Makespan'] for x in xs]):.0f} "
          f"hit_delta={hit:+.3f}pp wins={sum(1 for x in xs if x['c']['Makespan']<x['w']['Makespan'])}/{len(xs)} "
          f"WFD_hit={mean([x['w']['CacheHit'] for x in xs]):.3f} CA_hit={mean([x['c']['CacheHit'] for x in xs]):.3f}")

# seed=42 single run (the README/EXPERIMENT_REPORT "0.39%" claim)
print("\n--- single seed 42 (100 cases) ---")
import subprocess
print("(skipped: run separately)")