# Yat-CAShed

[![CI](https://github.com/mafuquan430/Yat-CAshed/actions/workflows/ci.yml/badge.svg)](https://github.com/mafuquan430/Yat-CAshed/actions/workflows/ci.yml)
[![License: GPL-2.0](https://img.shields.io/badge/kernel-GPL--2.0-blue.svg)](linux-6.8-main/COPYING)
[![Java](https://img.shields.io/badge/simulator-JDK%2017%2B-orange.svg)](simulator)
[![Kernel](https://img.shields.io/badge/kernel-Linux%206.8-informational.svg)](linux-6.8-main)

> **Yat-CAShed（Yet Another Task Cache-Aware Scheduler）**
> 一个面向多核 Linux 系统的缓存感知任务调度项目，涵盖内核调度器实现、调度算法模拟器与完整的论文/实验资料。

## 项目概述

Yat-CAShed 基于 AJLR（Allocation of parallel Jobs based on Learned cache Recency）思想，实现了一个内置于 Linux 内核的缓存感知调度类。其核心目标是：在**缓存亲和性**与**负载均衡**之间做动态权衡，减少任务跨核迁移导致的缓存失效，从而提升多核系统的整体性能。

- **调度策略 ID**：`SCHED_YAT_CASCHED = 8`
- **缓存感知机制**：维护任务历史表与加速表，记录 `last_cpu`、`per_cpu_recency` 等状态
- **核心算法**：基于 Cache Recency Profile（CRP）预测执行时间，结合 LCIF/MSF 策略做出（作业，核心）分配决策
- **工程实用性**：完整集成到 Linux 6.8 调度框架，可在 QEMU 中启动验证

### 本项目的四点不同

| 不同点 | 具体做法 | 为什么别人做不到/不常做 |
|---|---|---|
| 落在真内核，不是模拟器 | 实现为 `sched_class`（策略号 8），回调时机、锁上下文、亲和性语义全部由内核决定 | 仿真里"算法便宜"的结论，在真内核里会被接入成本吞掉；本文把两者分开报 |
| 代价可分解 | 三组配置消融（完整链路 / 回调不进 / 无唤醒），两两相减得到选核边际代价 ≈ 5% | 只报一个总比值的工作，无法回答"该优化哪里" |
| 结论可证伪 | 31 项 debugfs 计数器 + 3 个 tracepoint，每条断言都有对应读数与反例信号 | 计数器的确定性使"改完立刻判对错"成为可能，不必等统计显著 |
| 数字不可分叉 | 论文/README/说明书的数字由脚本从归档 CSV 重算，66 项比对失败即阻断 | 文档与数据的漂移是这类项目最常见的隐性错误 |

## 项目主页

- 🌐 **在线主页**：<https://donotcryok.github.io/Yat-CAShed/>（源码见 [`docs/index.html`](docs/index.html)）
  内容：项目定位、决策链路、实测结果、快速开始与文档导航。
  **首次使用需在仓库 Settings → Pages 把 Source 设为 `main` 分支的 `/docs` 目录**
  （仓库内已放好 `docs/.nojekyll`，不启用 Jekyll；页面是纯静态单文件、无外部字体/CDN，
  也可直接下载后用浏览器打开 `docs/index.html` 预览）。

## 文档导航

工程化文档统一收敛在 [`docs/`](docs) 目录，建议按以下顺序阅读：

| 文档 | 说明 |
|------|------|
| [`docs/README.md`](docs/README.md) | 文档总索引（入口） |
| [`docs/PROJECT_AUDIT.md`](docs/PROJECT_AUDIT.md) | 工程化审计快照（现状 / 问题清单 / 处理状态） |
| [`docs/DIRECTIONS.md`](docs/DIRECTIONS.md) | 改进与开发方向建议（候选方向 + 推荐路线） |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 系统架构与模块说明 |
| [`docs/DIRECTORY.md`](docs/DIRECTORY.md) | 目录结构详解 |
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | 环境搭建与构建指南 |
| [`docs/OPERATIONS.md`](docs/OPERATIONS.md) | **操作手册**（编译 / 验证 A8 / 读计数器 / 发补丁，照着敲即可） |
| [`docs/CONFLICT_ANALYSIS.md`](docs/CONFLICT_ANALYSIS.md) | 合并冲突分析与解决记录（已按路线 A 解决） |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | 任务化改进清单（P0–P3） |
| [`docs/EXPERIMENT_REPORT.md`](docs/EXPERIMENT_REPORT.md) | 模拟器实验报告（指标 / 统计 / 复现） |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 贡献规范（分支 / 提交 / 代码风格） |
| [`soft-copyright/README.md`](soft-copyright/README.md) | 软件著作权登记材料包（申请表稿 / 60 页源程序 / 21 页说明书 / 填报指引） |
| [`report/submission/`](report/submission) | 大创结题填报材料（项目执行情况 + 研究总结报告） |
| [`report/slides/`](report/slides) | 结题答辩 PPT 与讲稿（含 10 个可能追问及答法） |
| [`docs/结题材料清单.md`](docs/结题材料清单.md) | 结题材料逐项核对表（交什么 / 在哪 / 什么状态） |

> ✅ **状态**：`kernel/sched/yat_casched.c` 的 **10 处 Git 合并冲突已按「路线 A（决赛 AJLR 版）」全部解决**，
> 当前文件无冲突标记、符号一致性检查通过。决策依据与执行明细见 [`docs/CONFLICT_ANALYSIS.md`](docs/CONFLICT_ANALYSIS.md) §7–§8。

## 目录结构

```
Yat-CAShed/
├── README.md                    # 本文件
├── LICENSE / CONTRIBUTING.md    # 许可证说明 / 贡献规范
├── CHANGELOG.md                 # 变更日志
├── Makefile                     # 顶层编排（check / docs-check / simulator / report）
├── .editorconfig / .gitattributes / .gitignore
├── docs/                        # 工程化文档体系（入口见 docs/README.md）
├── patches/                     # 8 个可独立应用的内核补丁（替代内核整树入库）
├── soft-copyright/              # 软著登记材料包（申请表稿 / 60 页源码 / 21 页说明书）
├── scripts/                     # 仓库运维脚本
│   ├── check_repo.sh / check_symbols.sh / check_docs.py / check_names.py   # 门禁
│   ├── kernel-patch.sh / gen_kernel_patches.sh                            # 补丁应用/生成
│   ├── setup-kernel.sh                                                    # 从上游 v6.8 重建内核树
│   └── normalize_eol.py                                                   # 行尾归一化
├── .github/workflows/ci.yml     # CI（仓库检查 + 文档链接 + 命名 + 符号 + 补丁完整性 + 模拟器回放）
├── linux-6.8-main/              # Linux 6.8 内核源码 + Yat-CAShed 调度类
│   ├── kernel/sched/yat_casched.c          # 调度器核心实现（AJLR，冲突已解决）
│   ├── include/linux/sched/yat_casched.h   # 调度器头文件
│   ├── yat_casched优化记录.md               # 迭代优化记录
│   ├── boot_test_scripts/                   # 用户态测试程序
│   ├── yat_test_scripts/                    # QEMU 测试环境脚本
│   └── test_visualization/                  # 性能测试与可视化
├── simulator/                   # 缓存感知调度算法模拟器（Java 17+）
│   ├── Simulator.md                          # 模拟器详细文档
│   ├── AlgorithmComparisonExperiment.java    # 实验入口
│   ├── allocation/                           # WFD / CacheAware 算法实现
│   ├── tests/                                # 单元测试（116 断言）
│   └── lib/                                  # commons-math3（唯一运行时依赖）
└── report/                      # 论文、报告与参考资料
    ├── paper/                    # 毕业论文 LaTeX 源码（章节文件平铺）
    ├── submission/               # 大创结题填报稿（执行情况 + 研究总结，md + docx）
    ├── slides/                   # 结题答辩 PPT 与讲稿
    ├── prompt_md/                # 分析笔记与计划
    └── *.pdf / *.pptx            # 参考论文与早期材料
```

## 核心组件

### 1. 内核调度器（[`linux-6.8-main/`](linux-6.8-main)）

在 Linux 6.8 内核上新增 `SCHED_YAT_CASCHED`（策略号 8）调度类，主要改动点：

| 文件 | 改动 |
|------|------|
| [`kernel/sched/yat_casched.c`](linux-6.8-main/kernel/sched/yat_casched.c) | 调度器核心（历史表、加速表、就绪池、AJLR 调度） |
| [`include/linux/sched/yat_casched.h`](linux-6.8-main/include/linux/sched/yat_casched.h) | 数据结构与接口声明 |
| `kernel/sched/core.c` | 优先级验证、调度类注册、初始化 |
| `kernel/sched/sched.h` | `yat_casched_policy()` 与 `rq` 扩展 |
| `kernel/sched/Makefile` | 编译接入 `yat_casched.o` |
| `init/Kconfig` | `SCHED_CLASS_YAT_CASCHED` 配置项 |
| `include/uapi/linux/sched.h` | `SCHED_YAT_CASCHED = 8` |
| `include/linux/sched.h` | `task_struct` 增加 `yat_casched` 实体 |
| `include/asm-generic/vmlinux.lds.h` | 链接器段注册 |

> 架构与集成点说明见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)，迭代记录见 [`yat_casched优化记录.md`](linux-6.8-main/yat_casched优化记录.md)，
> 初赛版/决赛版的**历史**实现文档已归档到 [`docs/archive/`](docs/archive)。

### 2. 内核实测（QEMU，默认证据链）

在 QEMU（4 vCPU，TCG）上用同一份负载跑三组对照，每组重复 5 次，报墙钟比值
`yat/cfs` 的中位数与全距（原始 CSV 与串口日志见
[`docs/validation/2026-11-13/kernel/repeat/`](docs/validation/2026-11-13/kernel/repeat/)）：

| 配置 | 中位数 | 全距 | 回答的问题 |
|------|--------|------|-----------|
| `none` + 200 µs（主口径） | **1.81×** | [1.76, 2.13] | 完整链路的代价 |
| `shared` + 200 µs（回调不被调用） | **1.72×** | [1.64, 1.80] | 只有"接入调度类"的代价 |
| `none` + 无唤醒 | **0.84×** | [0.81, 1.17] | 唤醒路径的代价 |

> 这是**路线 A 落地后**的镜像 #20 数字（补丁 `0008`）。优化前的 #19 是 2.00 / 1.70 / 1.10，
> 两组的全距仍有重叠，所以 2.00 → 1.81 只作方向证据；确定的是 `balance_runs` 757 → 9。

**怎么核验这些数**：`make numbers-check` 把本节与论文里的数字逐项对回 `docs/validation/2026-11-13/kernel/`
下的归档 CSV（当前 66 项），`bash scripts/run_kernel_repeats.sh --repeats 5` 可重跑全部 15 次测量。
数字对不上，门禁就失败——这条链路不依赖人工复核。

**决策链路是通的**：主口径下 512 次唤醒触发 467 次选核（其余走 wakelist 快路径），
其中 295 次由"目标核空闲"直接复用，172 次完整决策全部落地，接管率
`906/924 = 98.1%`，平均可运行核数 4.0，`pick_null_desync = 0`。

**代价的归属**：pin 到单核（`select_calls = 0`，AJLR 一次都不跑）仍慢 1.72×，
说明主体代价来自“唤醒时进入这个调度类”；选核的边际从优化前的约 18%（2.00/1.70）
压到约 5%（1.81/1.72）。没有唤醒时两者无可分辨差异（0.84，全距 [0.81, 1.17] 跨 1）。
优化前 `balance_runs = 757` 而唤醒只有 512 次——几乎每个调度点都在重扫就绪池，
这正是路线 A 的切入点。

```bash
# 重跑全部 15 次测量（3 配置 × 5 次，约 30 分钟）
bash scripts/run_kernel_repeats.sh --repeats 5
```

> ⚠️ 本轮的实测都在 QEMU/TCG 上，墙钟与真实机器不可比，同配置三次可差 28%。
> 真机证据来自竞赛阶段：树莓派 5 上 TacleBench 12 类任务并发、10 轮平均，轮次用时 1550.8 → 1413.1 ms（−9.77%），
> 原始 CFS 基准日志与脚本归档在 [`docs/validation/pi-2025-07-30/`](docs/validation/pi-2025-07-30/README.md)，Yat 侧逐轮日志未留存。
> 因此只报中位数与全距、不报 p 值，也不把 5% 量级的差异当结论。

### 3. 前期工作：调度模拟器（[`simulator/`](simulator)）

项目早期用 Java 17 写过一个调度模拟器（6361 行，含 CSV 导出、t 区间统计与 116 项单测），
用于在不依赖内核的情况下比较 WFD 与缓存感知启发式。

**它已经不在证据链里**：模拟器的执行时间与命中判定都由自建缓存模型给出，不含抢占、
迁移、TLB 与真实 LLC 竞争；同一批数据在三套口径下分别是 11.66%、−0.95%、−3.52%，
说明它只能回答"在理想模型下某条规则会怎么表现"。论文正文只留一段交代（第 6.4 节），
数字与复现已归档在 [`docs/validation/2026-11-13/`](docs/validation/2026-11-13/)。

代码仍然可用，作为设计期校验工具：

```bash
make -C simulator test        # 116 项单测
make -C simulator verify      # 编译 + 依赖自检 + 单测 + 快速实验
make -C simulator run-quick   # 5 用例冒烟
make -C simulator run         # 全量实验（100 用例）+ 可视化
```

> 历史口径（`--legacy-metrics`、`--legacy-wfd-state`）保留为开关，只为复现旧数字，
> 不参与任何结论。模拟器的设计说明见 [`Simulator.md`](simulator/Simulator.md)，
> 早期实验报告见 [`docs/EXPERIMENT_REPORT.md`](docs/EXPERIMENT_REPORT.md)（顶部有时效声明）。

### 4. 论文与资料（[`report/`](report)）

- [`report/paper/`](report/paper)：毕业论文 LaTeX 源码（`Yat-CAShed.tex` 等章节文件）
- 参考论文：AJLR（RTNS'23）、Cache-Aware Allocation of Parallel Jobs on Multi-cores
- [`report/prompt_md/`](report/prompt_md)：分析与计划笔记
- 初赛版/决赛版**历史**实现文档：[`docs/archive/`](docs/archive)（不再是事实来源）

## 构建与测试（内核部分）

> 内核编译需在 Linux 环境（或 WSL）下进行，Windows 无法完整检出部分 Linux 专属文件名。

```bash
# 0) 最快的语法/类型检查（只编调度器目标文件，约 1 分钟）
make kernel-obj

# 1) 编译内核（产物 arch/x86/boot/bzImage）
make kernel-build

# 2) 验证 A8：AJLR 是否真的接管了选核
#    两个参数缺一不可：none（可跨核）+ sleep-us（制造 WF_TTWU）
linux-6.8-main/yat_test_scripts/compare_cfs_vs_yat.sh \
    --tasks 8 --iters 2000000 --affinity none --sleep-us 200 --timeout 300
```

实测基线（8 任务 / 4 核 / 2M 迭代 / 200 µs 唤醒，路线 A 之后）：`ttwu_ok 512`、
`select_calls 467`、`select_decided 172`、`jobs_placed 172`、`place_hit 906 / miss 0 / pending 18`
→ **接管率 98.1%**；主口径墙钟比中位数 **1.81**（全距 [1.76, 2.13]）。

完整的命令清单、计数器读法与故障排查见
[`docs/OPERATIONS.md`](docs/OPERATIONS.md)；测试脚本参数见
[`linux-6.8-main/yat_test_scripts/README.md`](linux-6.8-main/yat_test_scripts/README.md)。
用户态测试程序位于 [`boot_test_scripts/`](linux-6.8-main/boot_test_scripts)。

## 仓库健康检查

```bash
make check          # 冲突标记 / 空文件 / 关键文件
make docs-check     # Markdown 相对链接有效性
make numbers-check  # 论文/README 的数字 vs 归档 CSV 与计数器（66 项比对）
make symbol-check   # 内核头文件声明 vs 实现定义
make verify-all     # 以上 + 结构守卫 + 模拟器全套验证（CI 等价，共 8 道门禁）
```

- [`scripts/check_repo.sh`](scripts/check_repo.sh)：冲突标记、空文件、关键文件守卫
- [`scripts/check_docs.py`](scripts/check_docs.py)：全仓库自维护 Markdown 的相对链接检查
- [`scripts/check_numbers.py`](scripts/check_numbers.py)：从 `all.csv` 复算中位数/全距与派生百分比，
  再与论文第 6 章表格、摘要、结论、README 逐项比对——抄错一个数就失败
- [`scripts/check_symbols.sh`](scripts/check_symbols.sh)：核对 `yat_casched.h` 声明与 `yat_casched.c` 定义
  （当前 29 个声明 / 27 个非 static 定义 / 28 个 static 定义，A/B/C 三类不一致均为 0）
- 三者均已接入 CI（[`.github/workflows/ci.yml`](.github/workflows/ci.yml)）

论文编译：

```bash
make report      # 优先 latexmk -xelatex，产物 report/paper/Yat-CAShed.pdf
```

## 主要依赖

- **内核**：GCC/Binutils、bison、flex、libssl-dev、libelf-dev、bc、dwarves、QEMU、busybox
- **模拟器**：JDK 17+（`openjdk-17-jdk-headless` 即可）；
  运行时依赖仅 [`simulator/lib/commons-math3-3.6.1.jar`](simulator/lib)（详见 [`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md)）
- **可视化**：Python 3 + matplotlib（必需）/ pandas、seaborn（可选，缺失时自动降级）；安装 `pip install -r simulator/visualizer/requirements.txt`
- **论文**：TeX Live（`xelatex`、`ctex`、`latexmk` 可选）

## 许可证

分模块授权，详见 [`LICENSE`](LICENSE)：

- **内核部分**（`linux-6.8-main/` 中新增/修改的调度器文件）：GPL-2.0，与 Linux 内核一致（见 [`linux-6.8-main/COPYING`](linux-6.8-main/COPYING)）。
- **模拟器**（`simulator/` 自有 Java 源码）：学术研究与教学使用，保留所有权利。
  第三方依赖 `simulator/lib/commons-math3-3.6.1.jar` 归其作者所有（Apache-2.0）。
- **文档与论文**（`docs/`、`report/` 中本项目撰写部分）：学术研究与教学使用，转载请注明出处。
  `report/` 内引用的第三方论文 PDF 版权归原作者，仅作学习研究。

> 2026-10-24 复核：上文历史收益不能作为当前统一模型结论。五种子500配对用例的 Makespan 改善均值为 -0.954%，95%区间跨零；详见 [复核结果](docs/validation/2026-10-24/SUMMARY.md)。无图模式现已修复为正常导出 CSV。
