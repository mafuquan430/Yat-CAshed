# 真机阶段：树莓派 5 上的 TacleBench 对比（2025-07-30）

这是项目**真机阶段**的证据归档。它回答的问题与 QEMU 那批数据不同：

- QEMU/TCG 批次回答"代价有多大"（同一实现相对 CFS 慢多少、慢在哪）；
- 本目录回答"收益有没有"（有真实缓存层次时，缓存感知放置能不能省时间）。

## 平台

| 项 | 值 | 依据 |
|---|---|---|
| 机器 | 树莓派 5（ARM64，4 核） | 开发文档"开发环境：Vmware/树莓派 5 代" |
| 内核 | `6.8.0-1031-raspi` | `raw/cfs_baseline_20250730_150206.log` 首部自证 |
| 基准 | TACLeBench（54 个程序：29 kernel + 23 sequential + 2 app） | `raw/测试环境说明.md` |
| 日期 | 2025-07-30 | 日志文件名与时间戳 |

## 主结果：TacleBench 12 类任务并发对比

负载为 12 类计算任务（binarysearch、bitcount、bitonic、bsort、complex_updates、cosf、
countnegative、cubic、deg2rad、fac、fft、filterbank），每类数十个并发副本，共 10 轮，
WCET 由每任务 100 次运行取最大值再加 10% 裕度得到。

| 指标 | CFS | Yat-CAshed | 变化 |
|---|---|---|---|
| 平均轮次用时 | 1550.8 ms | 1413.1 ms | **−9.77%** |
| 每轮节省 | — | 137.7 ms | — |
| 等效加速比 | 1.000 | **1.108×** | — |

## 证据等级（务必按此引用）

| 结论 | 等级 | 说明 |
|---|---|---|
| 9.77% 轮次用时改善 | **汇总结论** | 出自竞赛阶段开发文档的表 10；Yat 侧逐轮原始日志未留存，无法逐轮重算 |
| CFS 基准 54 程序耗时 | **原始数据** | `raw/cfs_baseline_20250730_150206.log`，可直接复算每程序用时 |
| 平台与内核版本 | **原始数据** | 同上日志首部 |
| 本轮（2026-11）真机复测 | **未做** | 板子已不在手上；重跑需同型号机器 + `raw/test_yat_scheduler.sh` |

**引用规则**：写"在树莓派 5 上轮次用时降低 9.77%（10 轮平均）"可以；写"缓存命中率提升
9.77%"或"统计显著"不可以——后者没有数据支撑。

## 目录内容

| 文件 | 是什么 |
|---|---|
| `raw/cfs_baseline_20250730_150206.log` | 54 个 TACLeBench 程序在 CFS 下的逐程序用时（原始日志） |
| `raw/test_yat_scheduler.sh` | Yat 侧测试脚本（输出 `yat_test_results/yat_performance_<日期>.log`，该日志未留存） |
| `raw/测试环境说明.md` | 54 程序构成、缓存敏感程序清单、测试方式 |
| `raw/yat_simple_qemu_arm64.log` | ARM64 引导日志（证明内核在 ARM64 上起来并跑完测试任务） |

## 怎么复现

```bash
# 在树莓派上，内核切到 Yat（SCHED_YAT_CASCHED=8）
cd ~/tacle-bench
./taclebench_test.sh full          # 先取 CFS 基准
./test_yat_scheduler.sh            # 再跑 Yat，结果落在 yat_test_results/
```

对照时用"平均轮次用时"，不要用单个程序的用时：单程序顺序跑时只有一个可运行任务，
选核没有选择空间，缓存亲和几乎不起作用。