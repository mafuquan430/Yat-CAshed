#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run_kernel_repeats.sh —— 内核实测的重复测量
#
# 单次 QEMU/TCG 运行的墙钟噪声很大（同配置三次可差 28%），单点数字不足以支撑
# "比 CFS 慢多少"这种结论。本脚本对每个配置重复 N 次，输出中位数与全距，
# 并把每轮的原始 CSV 留档，便于事后复算。
#
# 用法：
#   scripts/run_kernel_repeats.sh --repeats 5 --out docs/validation/2026-11-13/kernel/repeat
#
# 产物：
#   <out>/<tag>/run-<i>.csv    每轮的两行原始数据
#   <out>/all.csv              全部轮次汇总（ratio 列为 yat/cfs 的 sum_wall 比）
#   <out>/SUMMARY.txt          每配置的中位数/最小/最大
# ---------------------------------------------------------------------------
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO/docs/validation/2026-11-13/kernel/repeat"
REPEATS=5
TASKS=8
ITERS=2000000
TIMEOUT=300

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repeats) REPEATS="$2"; shift 2 ;;
    --out)     OUT="$2"; shift 2 ;;
    --tasks)   TASKS="$2"; shift 2 ;;
    --iters)   ITERS="$2"; shift 2 ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

KERNEL="$REPO/linux-6.8-main/arch/x86/boot/bzImage"
SCRIPT="$REPO/linux-6.8-main/yat_test_scripts/compare_cfs_vs_yat.sh"
mkdir -p "$OUT"
ALL="$OUT/all.csv"
echo "tag,run,policy,sum_wall_ns,sum_work_units,vol_ctx,invol_ctx,ratio_yat_over_cfs" > "$ALL"

# 三个配置：主口径 / 回调不被调用（单核 pin）/ 无唤醒对照
TAGS=("none-sleep200" "shared-sleep200" "none-sleep0")
ARGS=("--affinity none --sleep-us 200"
      "--affinity shared --sleep-us 200"
      "--affinity none --sleep-us 0")

for idx in "${!TAGS[@]}"; do
  tag="${TAGS[$idx]}"
  mkdir -p "$OUT/$tag"
  for i in $(seq 1 "$REPEATS"); do
    echo "[$tag] run $i/$REPEATS"
    # shellcheck disable=SC2086
    "$SCRIPT" --tasks "$TASKS" --iters "$ITERS" --kernel "$KERNEL" \
              --timeout "$TIMEOUT" ${ARGS[$idx]} > "$OUT/$tag/run-$i.log" 2>&1
    rc=$?
    if [[ $rc -ne 0 ]]; then
      echo "  run failed rc=$rc (see $OUT/$tag/run-$i.log)" >&2
      continue
    fi
    cp "$REPO/linux-6.8-main/yat_test_scripts/results/cfs_vs_yat.csv" "$OUT/$tag/run-$i.csv"
    python3 - "$OUT/$tag/run-$i.csv" "$tag" "$i" >> "$ALL" <<'PY'
import csv, sys
path, tag, run = sys.argv[1], sys.argv[2], sys.argv[3]
rows = {r["policy"]: r for r in csv.DictReader(open(path))}
if "cfs" not in rows or "yat" not in rows:
    sys.exit(0)
c, y = rows["cfs"], rows["yat"]
ratio = float(y["sum_wall_ns"]) / float(c["sum_wall_ns"])
for name in ("cfs", "yat"):
    r = rows[name]
    print("%s,%s,%s,%s,%s,%s,%s,%s" % (tag, run, name, r["sum_wall_ns"],
          r["sum_work_units"], r["vol_ctx"], r["invol_ctx"],
          ("%.4f" % ratio) if name == "yat" else ""))
PY
  done
done

python3 - "$ALL" "$OUT/SUMMARY.txt" <<'PY'
import csv, sys, statistics as st
rows = list(csv.DictReader(open(sys.argv[1])))
tags = []
for r in rows:
    if r["tag"] not in tags:
        tags.append(r["tag"])
out = []
out.append("%-16s %6s %10s %10s %10s" % ("config", "n", "median", "min", "max"))
for t in tags:
    vals = [float(r["ratio_yat_over_cfs"]) for r in rows if r["tag"] == t and r["ratio_yat_over_cfs"]]
    if not vals:
        continue
    out.append("%-16s %6d %10.3f %10.3f %10.3f" % (t, len(vals), st.median(vals), min(vals), max(vals)))
open(sys.argv[2], "w").write("\n".join(out) + "\n")
print("\n".join(out))
PY