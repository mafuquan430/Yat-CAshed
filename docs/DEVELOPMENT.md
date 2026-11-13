# 开发环境与构建指南

本指南描述 Yat-CAShed 三大模块（内核、模拟器、可视化/报告）的环境准备与常用命令。

## 1. 快速命令（顶层 Makefile）

仓库根目录新增了 [`Makefile`](../Makefile) 作为统一入口（对既有脚本的薄封装）：

```bash
make help              # 查看所有可用命令
make check             # 仓库健康检查（冲突标记、空文件、结构）
make symbol-check      # 内核头文件/实现符号一致性检查（静态）
make simulator-compile # 仅编译 Java 模拟器
make simulator-test    # 运行模拟器单元测试（确定性核心逻辑）
make simulator-quick   # 轻量可复现实验（快速）
make simulator-verify  # 编译 + 依赖自检 + 单元测试 + 快速实验
make simulator         # 全量运行 Java 模拟器（转发到 simulator/Makefile）
make visualize         # 生成模拟器可视化图表
make report            # 编译 LaTeX 论文（需 xelatex）
make clean             # 清理各类构建产物
```

> 内核编译是重量级操作（耗时数十分钟、需要数 GB 空间），未纳入顶层 `make all`，请按下文在内核目录内执行。

## 2. 内核模块（`linux-6.8-main/`）

### 依赖

```bash
sudo apt update
sudo apt install -y build-essential libncurses5-dev libssl-dev libelf-dev \
    bison flex bc dwarves fakeroot
sudo apt install -y qemu-system-x86 qemu-utils busybox-static cpio gzip
```

### 编译

```bash
cd linux-6.8-main

# 启用调度类
scripts/config --enable CONFIG_SCHED_CLASS_YAT_CASCHED

# 快速验证单文件（推荐，秒级反馈）
make kernel/sched/yat_casched.o

# 全量编译
make -j"$(nproc)"
```

### 运行测试（QEMU）

两种入口，用途不同：

```bash
# A. 交互式探索（手动进 shell、看 proc/debugfs、跑单条命令）
cd yat_test_scripts
./create_test_initramfs.sh       # 生成 initramfs
./start_test_environment.sh      # 启动 QEMU
# 退出：exit → Ctrl+A → X

# B. 出数据（两个独立 QEMU 进程，唯一变量是调度策略，自动落 CSV）
cd yat_test_scripts
./compare_cfs_vs_yat.sh --tasks 8 --iters 2000000 \
                        --affinity none --sleep-us 200 --timeout 300
```

> 要验证 AJLR（A8）必须用 B，且 `--affinity none` 与 `--sleep-us > 0` **缺一不可**：
> `shared` 会把 `nr_cpus_allowed` 压到 1 使调度类回调不被调用，`sleep-us 0` 则没有
> `WF_TTWU` 唤醒。完整命令与计数器读法见 [`OPERATIONS.md`](OPERATIONS.md)。

> ✅ `kernel/sched/yat_casched.c` 的合并冲突已按「路线 A（决赛 AJLR 版）」解决，文件可直接参与编译。
> 决议与遗留项见 [CONFLICT_ANALYSIS.md](CONFLICT_ANALYSIS.md) §7–§8。
>
> ✅ **行尾已归一化**：仓库通过 [`.gitattributes`](../.gitattributes) 强制 `* text=auto eol=lf`，
> 并提供 [`scripts/normalize_eol.py`](../scripts/normalize_eol.py) 用于历史文件批量修复；
> 全新 `git clone` 后可直接 `make`，无需再手工 `sed` 去除 CRLF。
> 若在非 Git 途径（如直接拷贝压缩包）获取源码后仍遇到 CRLF 问题，可执行：
> `python3 scripts/normalize_eol.py --apply`。

## 3. 模拟器（`simulator/`，Java 17+）

[`simulator/Makefile`](../simulator/Makefile) 已改写为**跨平台 GNU Make**（自动识别 OS、JDK、类路径分隔符），
在 Linux / macOS / WSL / Git-Bash 下均可直接使用；Windows 亦可沿用 `build.bat`。

```bash
# 方式一：仓库根目录（推荐）
make simulator-compile   # 编译（23 个类）
make simulator-quick     # 轻量回放：5 用例 / 20 任务 / 种子 42 / 跳过可视化
make simulator-verify    # 编译 + Apache Commons Math 自检 + 强制重编译

# 方式二：进入 simulator 目录
cd simulator
make help                # 查看全部目标
make compile             # 编译
make lib-test            # 依赖库自检
make test                # 单元测试（tests/YatSimulatorTest.java，116 断言）
make run-quick           # 轻量实验
make run                 # 全量实验（100 用例）+ 可视化 + 3 张 300dpi 图表
make picture             # 单独生成图表

# Windows
build.bat run
```

可调参数（详见 `make help` 与 [`README.md`](../README.md)）：

| 参数 | 默认 | 说明 |
|------|------|------|
| `--cores=` `--cases=` `--tasks=` | 8 / 100 / 60 | 规模 |
| `--seed=` `--seeds=` | 42 | 单种子 / 多种子（`--seeds=5` 表示 1..5） |
| `--no-viz` | 关闭 | 跳过 Python 出图 |
| `--legacy-metrics` | 关闭 | 旧的不公平度量口径，仅用于复现历史数字 |
| `--allocator=v2\|v3` | v2 | 挑战者；v3 = AJLR-lite |
| `--policy=NAME` | HEAD_ROOM | v3 判定规则（或 MIN_FINISH） |
| `--alpha=R` | 0.5 | v3 负载窗口 |
| `--premium=R` | 1.0 | v3 的 CRP 溢价缩放 |
| `--legacy-wfd-state` | 关闭 | WFD 负载跨用例不清零的旧基线口径（复现归档用） |

> 默认基线是"每个用例前复位 WFD"。`--legacy-wfd-state` 与 `--legacy-metrics` 都只为复现
> 历史数字而保留；当前结论见
> [`validation/2026-11-13/SUMMARY.md`](validation/2026-11-13/SUMMARY.md)。

> 环境依赖：仅需 JDK 17+（`sudo apt install -y openjdk-17-jdk-headless`）。
> Maven/Gradle 非必需，第三方 JAR 已内置于 `simulator/lib/`。

可视化（Python 3 + matplotlib 必需，pandas/seaborn 可选）：

```bash
pip install -r simulator/visualizer/requirements.txt
cd simulator && python3 visualizer/visualize.py result/algorithm_comparison_results.csv
```

> Java 实验结束时会自动探测 `python3` → `python` 并调用 `visualizer/visualize.py`；
> 若均缺失则自动跳过，不影响数值结果与 CSV 导出。
> `visualize.py` 已兼容 matplotlib ≥ 3.9（`boxplot` 的 `labels` 参数改用 `set_xticklabels`）。
> 全量实验（`make run`）会产出 CSV 与 3 张图表至 `simulator/result/`（该目录已被 `.gitignore` 忽略）。

## 4. 论文（`report/paper/`）

```bash
# 需安装 TeX Live（含 xelatex + ctex 宏包 + latexmk，可选）
make report              # 仓库根目录执行，内部优先 latexmk -xelatex

# 或手工执行
cd report/paper
latexmk -xelatex Yat-CAShed.tex   # 推荐：自动多遍 + 交叉引用
# 无 latexmk 时：xelatex Yat-CAShed.tex 执行两遍
```

产物：`report/paper/Yat-CAShed.pdf`（约 25 页）。
章节文件与主文件同目录平铺（`chapter1-intro.tex` … `chapter7-difficulties.tex`），
主文件通过 `\input{<文件名>}` 引用。

## 5. 仓库健康检查（CI 同款）

`.github/workflows/ci.yml` 会在 push / PR 时执行：

1. **仓库健康检查**：`bash scripts/check_repo.sh`（冲突标记、空文件、结构）。
2. **文档链接检查**：`python3 scripts/check_docs.py`（Markdown 相对链接必须可解析）。
3. **符号一致性检查**：`bash scripts/check_symbols.sh`（头文件声明 vs 实现定义、链接属性一致性）。
4. **冲突标记独立复核**：扫描 `<<<<<<<` / `>>>>>>>`（**无白名单**，任何冲突一律阻断）。
5. **模拟器构建与回放**：`make -C simulator compile` → `lib-test` → `test` → `run-quick`。

本地等价命令：

```bash
make check                     # 或 bash scripts/check_repo.sh
make docs-check                # 或 python3 scripts/check_docs.py
make symbol-check              # 或 bash scripts/check_symbols.sh
make simulator-verify
make verify-all                # 以上全部串行执行
```

> 合并冲突已全部解决，接口声明与实现一致，无白名单例外。
> LaTeX 编译未纳入 CI（体积与耗时原因），但 `make report` 已可稳定复现。

## 6. 优化记录：就绪代际门禁（Route A，镜像 #20 起）

### 6.1 问题

`balance_yat_casched()` 是调度类在每个调度点都会走的回调。原实现无条件调用
`yat_update_ready_pool()`，而后者要：

1. 取全局的 `global_dag_lock`；
2. 遍历 `global_dag_list` 上的每个 DAG；
3. 对每个 DAG 取 `dag->lock`，再遍历它下面的**所有**作业；
4. 对每个 `WAITING && pending_predecessors == 0` 的作业调 `yat_ready_pool_enqueue()`。

主口径实测 `balance_runs = 757`，而唤醒只有 512 次——也就是说约 1/3 的 balance
调用发生时，池里根本没有新就绪的作业，上面四步纯属白做。在 TCG 下每次取自旋锁
都要跨 vCPU，这笔开销不可忽略。

### 6.2 机制：一个代际号

```c
static atomic_t yat_ready_gen   = ATOMIC_INIT(0);   /* 就绪事件代际 */
static atomic_t yat_scanned_gen = ATOMIC_INIT(-1);  /* 上次扫描看到的代际 */
```

- **推进**（只有两个地方会让作业"变成就绪"）：
  - `__yat_create_job()`：新建作业且 `pending_predecessors == 0`；
  - `yat_job_complete()`：某后继的最后一个前驱完成，计数减到 0。
- **消费**：`yat_ready_scan_needed()` 读代际，与 `yat_scanned_gen` 比较；
  不同则把后者推进到当前快照并返回 `true`。
- **快速路径**：`balance_yat_casched()` 在**取 `yat_balance_lock` 之前**判断
  "池空 + 代际未变"，成立就直接返回，只加一个 `balance_noop` 计数。

### 6.3 为什么不会漏作业

| 担心的情况 | 为什么不发生 |
|---|---|
| 扫描期间又有作业就绪 | 代际又变了，下一轮 balance 必然重扫（`yat_scanned_gen` 停在旧值） |
| 重复扫描把同一作业入池两次 | `yat_ready_pool_enqueue()` 前有 `state == WAITING` 判断，入池后置 READY |
| 池里还有作业但被快速路径跳过 | 快速路径要求 `nr_jobs == 0`，池非空一律走完整路径 |
| 找不到核心的作业被丢弃 | 这类作业被 `enqueue` 放回池中，`nr_jobs > 0`，不会被跳过 |

### 6.4 验收读数

主口径单次（镜像 #20，`--affinity none --sleep-us 200`）：

| 计数器 | 改前（#19） | 改后（#20） | 读法 |
|---|---|---|---|
| `balance_runs` | 757 | 9 | 真正做全局分配的轮次 |
| `balance_noop` | —— | 634 | 池空且无新就绪，连锁都没取的轮次 |
| `jobs_placed` | 271 | 207 | 实际落地分配数 |
| `place_hit / pending` | 996 / 21 | 903 / 15 | 接管率 97.9% → 98.4% |
| 墙钟比（中位数，5 次） | 2.00 | 1.81 | 全距 [1.76, 2.13] |
| 选核边际代价 | ≈18% | ≈5% | 1.81/1.72 − 1 |

注意 `jobs_placed` 变小**不是**退步：旧实现每轮都把已在睡眠的任务重新"放置"一遍，
这些放置没有任何作用，只是把计数器撑大。

### 6.5 复算

```bash
make kernel-obj && make kernel-build          # 镜像 #20
bash scripts/run_kernel_repeats.sh --repeats 5 \
     --out docs/validation/2026-11-13/kernel/repeat-routeA
python3 scripts/check_numbers.py              # 会同时校验两个目录
```

差分对比：

```bash
diff -u docs/validation/2026-11-13/kernel/repeat/SUMMARY.txt \
        docs/validation/2026-11-13/kernel/repeat-routeA/SUMMARY.txt
```

## 7. 命名与提交规范

- 项目名统一为 **Yat-CAShed**（文档标题中可用 `Yat-CAShed`，但代码标识符统一 `yat_casched`）。
- 提交信息采用约定式前缀（沿用现有风格）：
  `feat:` / `fix:` / `docs:` / `chore:` / `refactor:` / `test:`。
- 详细贡献流程见 [`CONTRIBUTING.md`](../CONTRIBUTING.md)。
