# Yat-CAShed 用户态测试程序（只放 C 源码）

## 目录约定（重要）

本仓库对测试相关目录做了**职责切分**，这是 A7「测试体系统一」的成果：

| 目录 | 只放什么 | 不放什么 |
|------|----------|----------|
| [`boot_test_scripts/`](.) | **C 源码**（`*.c`）与本说明 | 任何编译产物、任何 `.sh` 脚本 |
| [`../yat_test_scripts/`](../yat_test_scripts) | **Shell 脚本**（编译 / 打包 initramfs / 启动 QEMU / 跑对比） | 测试程序的 C 源码 |

早期版本把 `.sh` 脚本放在本目录，并且在 `gcc -o "$BOOT_DIR/$name"` 时把二进制直接写到
源码目录里。这两点都已修正：

- 四个脚本已 `git mv` 迁到 [`../yat_test_scripts/`](../yat_test_scripts)；
- [`create_test_initramfs.sh`](../yat_test_scripts/create_test_initramfs.sh) 现在把可执行文件编译到
  `test_with_programs_build/`（已 gitignore），本目录**物理上**只剩 `.c` 与 Markdown。

因此，如果在本目录看到可执行文件，说明是历史残留，可安全删除。

## 目录内容

```
boot_test_scripts/
├── yat_simple_test.c        # 冒烟测试：5 进程并发，逐个 sched_setattr 切到 YAT 策略
├── yat_bench.c              # 性能基准：N 进程 × M 次迭代，输出机器可解析的 YATCSV 行
├── README.md                # 本文件
└── SIMPLE_TEST_README.md    # 冒烟测试的最简操作步骤（历史命名，内容仍有效）
```

## 两个程序分别做什么

### `yat_simple_test.c` —— 能不能跑起来

创建若干子进程，逐个用 `sched_setattr(2)` 把调度策略切到 `SCHED_YAT_CASCHED`（编号 8），
打印每个进程的 PID、`sched_getcpu()` 结果与策略设置返回值。用途是**功能性验证**：
调度类是否注册、`sched_setattr` 是否被接受、任务是否真的被调度到 CPU 上。

### `yat_bench.c` —— 快不快，以及 AJLR 有没有生效

同一份固定工作量（LCG + 异或混合，避免被编译器优化掉），N 个子进程并行跑 `iters` 次，
父进程通过 pipe 收回每个子进程的墙钟时间，聚合成一行 CSV。

#### 命令行参数

| 参数 | 默认 | 说明 |
|------|------|------|
| `--policy cfs\\|yat` | `cfs` | 目标策略（由内核命令行 `yatbench=` 传入，保证同一二进制） |
| `--tasks N` | 4 | 并发子进程数（上限 `MAX_TASKS`） |
| `--iters M` | 20000000 | 每个子进程的固定迭代次数 |
| `--cpu C` | 0 | `--affinity shared` 时 pin 到哪个物理 CPU |
| `--affinity shared\\|none\\|spread` | `shared` | 亲和性模式，见下表 |
| `--sleep-us U` | 0 | 每段计算后 `nanosleep` U 微秒，制造真实唤醒；计算固定切 64 段 |
| `--verbose` | 关 | 逐阶段打印 `YTASK` 标记，仅用于 guest 挂死时定位 |

亲和性模式的区别（**这条直接决定能不能测到 AJLR**）：

| 模式 | 行为 | `nr_cpus_allowed` | 能测什么 |
|------|------|-------------------|----------|
| `shared` | 全部 pin 到 `--cpu` | 1 | 同核竞争下的时间片分配；**测不到 AJLR**（`core.c` 的包装层直接 `cpumask_any()` 返回，不调用调度类） |
| `none` | 不设亲和性 | 在线 CPU 数 | 跨核放置；A8 验证**必须**用这个 |
| `spread` | 第 i 个任务 pin 到 CPU `i % 在线数` | 1 | 强制初始分散，观察是否会被重新收敛 |

> 另外 `--sleep-us 0` 时任务从不阻塞，只有 `WF_FORK`，而
> `select_task_rq_yat_casched()` 刻意在非 `WF_TTWU` 早退 —— 所以
> **验证 AJLR 必须同时满足 `--affinity none` 与 `--sleep-us > 0`**。
> 早期版本把任务固定 pin 到单核且从不阻塞，两个条件同时不成立，
> 这正是历史上「`jobs_placed` 恒 0」的直接原因（详见仓库
> [`docs/ROADMAP.md`](../../docs/ROADMAP.md) §A8.1）。

#### 输出

```
YATCSV,policy,tasks,iters,cpu,sum_wall_ns,min_wall_ns,max_wall_ns,sum_work_units,
       vol_ctx,invol_ctx,cpus_online,affinity,sleep_us
```

（实际是一行，这里为可读性折行；共 **13 列**。）

| 字段 | 说明 |
|------|------|
| `policy` | `cfs` 或 `yat`（由内核命令行 `yatbench=` 传入） |
| `sum_wall_ns` | N 个子进程墙钟时间之和（ns），**主指标** |
| `min/max_wall_ns` | 单个子进程的最快 / 最慢墙钟时间（反映离散度） |
| `sum_work_units` | 校验和：应为 `tasks × iters`，用于确认真的跑完了 |
| `vol_ctx` / `invol_ctx` | 自愿 / 非自愿上下文切换次数（读 `/proc/self/status`） |
| `cpus_online` | guest 内 `_SC_NPROCESSORS_ONLN`；**必须 > 1**，否则对比无意义 |
| `affinity` / `sleep_us` | A8 阶段追加的自描述列（加在行尾，旧列位置不变），用于事后判断这组数据能否评价 AJLR |

退出码：`0` 成功；`2` 参数或调用失败；`3` 当前内核没有 `SCHED_YAT_CASCHED`。

> 该程序被 [`compare_cfs_vs_yat.sh`](../yat_test_scripts/compare_cfs_vs_yat.sh) 在 guest 内调用，
> 由内核命令行参数决定跑 CFS 还是 YAT，从而保证**同一二进制、同一内核、同一负载**，
> 唯一变量是调度策略。

## 怎么用

所有操作都在 [`../yat_test_scripts/`](../yat_test_scripts) 里发起：

```bash
cd linux-6.8-main/yat_test_scripts

# 冒烟测试：编译 + 打包 initramfs + 启动 QEMU
./build_yat_simple_test.sh
./start_yat_simple_test.sh

# 性能对比：自动跑 CFS 与 YAT 两轮 QEMU，产出 CSV
./compare_cfs_vs_yat.sh --iters 2000000

# 验证 AJLR（A8）：必须可跨核 + 有唤醒
./compare_cfs_vs_yat.sh --tasks 8 --iters 2000000 \\
                        --affinity none --sleep-us 200 --timeout 300

# 或者从仓库根目录用 Makefile
make kernel-compare
```

手工编译单个程序（产物**不要**落在本目录）：

```bash
gcc -O2 -static -Wall -Wextra \
    -o /tmp/yat_bench linux-6.8-main/boot_test_scripts/yat_bench.c
```

## 前置条件

1. 内核已编译出 `arch/x86/boot/bzImage`（见 [`../../docs/DEVELOPMENT.md`](../../docs/DEVELOPMENT.md)）；
2. 已安装 `qemu-system-x86`、`gcc`、`cpio`、`gzip`、`busybox-static`；
3. 内核配置启用 `CONFIG_SCHED_CLASS_YAT_CASCHED=y`，
   并建议同时开 `CONFIG_DEBUG_FS=y`（A6 的可观测性节点依赖它）。

## 相关文档

- 脚本清单与用法：[`../yat_test_scripts/README.md`](../yat_test_scripts/README.md)
- 性能对比实验设计与结论：[`../../docs/EXPERIMENT_REPORT.md`](../../docs/EXPERIMENT_REPORT.md)
- 性能测试与图表：[`../test_visualization/README.md`](../test_visualization/README.md)
- 内核构建说明：[`../../docs/DEVELOPMENT.md`](../../docs/DEVELOPMENT.md)
