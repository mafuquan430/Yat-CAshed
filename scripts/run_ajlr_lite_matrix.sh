#!/usr/bin/env bash
#
# 复现第\ref{sec:test}节的全部模拟器实验。
#
# 输出目录：docs/validation/2026-11-13/{main,matrix}/<tag>/
#   - ajlr_lite_seed_<N>.csv   三算法配对结果（WFD / CacheAware_v2 / CacheAware_v3）
#   - run.log                 该配置的完整终端输出（含区间估计）
#
# 约定：
#   * 全部配置共用同一批种子与同一套生成器参数，唯一变量是策略与基线口径；
#   * `legacy` 配置复刻主实验的 WFD 状态残留（跨用例不清静态负载），
#     它存在的唯一目的是证明主实验数字的来源，不作为推荐口径。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SIM="$ROOT/simulator"
OUT="$ROOT/docs/validation/2026-11-13/matrix"
CP="classes:lib/*:."
SEEDS="1,2,3,4,5"
CASES="${CASES:-100}"
TASKS="${TASKS:-60}"

cd "$SIM"
[ -d classes ] || make compile

run() {
  local tag="$1"; shift
  mkdir -p "$OUT/$tag"
  echo ">>> $tag : $*"
  java -cp "$CP" AjlrLiteExperiment --seeds="$SEEDS" --cases="$CASES" --tasks="$TASKS" \
       --out="$OUT/$tag" "$@" 2>&1 | tee "$OUT/$tag/run.log"
}

# 1) 复刻主实验口径：WFD 静态负载跨用例残留
run legacy      --legacy-wfd-state --policy=HEAD_ROOM --alpha=1.0
# 2) 干净基线：每用例复位 WFD
run min-finish  --policy=MIN_FINISH
run hr-a025     --policy=HEAD_ROOM --alpha=0.25
run hr-a050     --policy=HEAD_ROOM --alpha=0.50
run hr-a100     --policy=HEAD_ROOM --alpha=1.00
run hr-a200     --policy=HEAD_ROOM --alpha=2.00
# 3) CRP 溢价敏感性（默认工作点 alpha=0.5）
run hr-a050-p050 --policy=HEAD_ROOM --alpha=0.50 --premium=0.5
run hr-a050-p150 --policy=HEAD_ROOM --alpha=0.50 --premium=1.5

echo
echo ">>> 完成。汇总：python3 scripts/analyze_ajlr_lite.py"