import analyzer.ConfidenceInterval;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import allocation.AllocationMethods;
import allocation.CacheAware_v2;
import allocation.CacheAware_v3;
import allocation.WFD;
import analyzer.PerformanceAnalyzer;
import analyzer.PerformanceAnalyzer.ExperimentResult;
import entity.Node;
import entity.SharedCacheModel;
import generator.EnhancedTaskGenerator;
import parameters.ExperimentConfig;
import parameters.SystemParameters;
import visualizer.ResultVisualizer;

/**
 * 算法性能对比实验主类
 * 
 * 该类实现了WFD算法与Cache-Aware算法的全面性能对比实验。
 * 通过大规模的测试案例和多维度的性能指标评估，验证Cache-Aware算法的性能优势。
 * 
 * 实验设计特点：
 * - 多利用率级别测试：覆盖不同系统负载情况
 * - 大规模测试案例：确保统计结果的可靠性
 * - 缓存敏感任务生成：突出缓存算法的优势
 * - 多维度性能评估：从makespan到缓存命中率的全面分析
 * - 实时进度监控：提供详细的实验进度和中间结果
 * - 自动化可视化：生成图表和CSV数据用于进一步分析
 * 
 * 核心实验参数：
 * - 处理器核心数：8核
 * - 测试案例数：100个
 * - 每案例任务数：60个
 * - 利用率级别：0.6, 0.8, 1.0, 1.2, 1.5
 * - 高缓存敏感任务比例：70%
 * 
 * 评估指标：
 * 1. Makespan（总完成时间）
 * 2. 缓存命中率
 * 3. CPU利用率
 * 4. 负载均衡度
 * 5. 算法执行时间
 * 6. 胜率统计
 * 
 * @author Cache-Aware Task Scheduling System
 */
public class AlgorithmComparisonExperiment {    
    // ==================== 实验配置（B3：外置到 config/*.yaml） ====================
    //
    // 这些字段不再硬编码：先取 ExperimentConfig 的内置默认值，再由 --config 指向的
    // YAML 覆盖，最后由命令行覆盖。三者优先级在 ExperimentConfig 的类注释里有说明。
    //
    // 保留 `private static` 字段形态（而不是到处传 CONFIG）是为了让本轮改动只触及
    // 配置装配点，不改动实验主体的计算路径——降低回归风险。

    /** 当前生效的配置（含来源信息，供启动时打印）。 */
    private static ExperimentConfig CONFIG = ExperimentConfig.defaults();

    /** 系统处理器核心数量（config: experiment.cores / --cores=） */
    private static int CORES = ExperimentConfig.DEFAULT_CORES;

    /** 总测试案例数量（config: experiment.total_test_cases / --cases=） */
    private static int TOTAL_TEST_CASES = ExperimentConfig.DEFAULT_TOTAL_TEST_CASES;

    /** 每个测试案例的任务数量（config: experiment.tasks_per_case / --tasks=） */
    private static int TASKS_PER_CASE = ExperimentConfig.DEFAULT_TASKS_PER_CASE;

    /** 随机种子（config: experiment.seed / --seed=，保证可复现） */
    private static long SEED = ExperimentConfig.DEFAULT_SEED;

    /** 是否生成可视化（config: experiment.enable_viz / --no-viz 关闭） */
    private static boolean ENABLE_VIZ = ExperimentConfig.DEFAULT_ENABLE_VIZ;

    /**
     * 是否使用旧的（不公平）度量口径，仅用于复现历史结果做对比。
     *
     * <p>开启后：WFD 走原始 {@code expectedET}、CacheAware 走
     * {@code CacheAware_v2.calculateExecutionTime()}，且命中率由
     * {@code PerformanceAnalyzer} 按算法名估算。默认关闭，即公平基线（C1）。
     */
    private static boolean LEGACY_METRICS = ExperimentConfig.DEFAULT_LEGACY_METRICS;

    /** 多随机种子（config: experiment.seeds / --seeds=1,2,3）；为 null 表示只用 seed。 */
    private static long[] SEEDS = null;

    /**
     * 系统利用率测试级别数组（config: experiment.utilization_levels）。
     * 覆盖从轻载到重载的不同系统负载情况；顺序即执行顺序。
     *
     * <p>注意：由 {@code final} 改为可配置——这正是 B3 要解掉的一个结。
     */
    private static double[] UTILIZATION_LEVELS = ExperimentConfig.DEFAULT_UTILIZATION_LEVELS.clone();

    /**
     * 高缓存敏感任务比例（config: experiment.high_cache_sensitivity_ratio）。
     * 用于突出 Cache-Aware 算法的优势；同样由 {@code final} 改为可配置。
     */
    private static double HIGH_CACHE_SENSITIVITY_RATIO =
            ExperimentConfig.DEFAULT_HIGH_CACHE_SENSITIVITY_RATIO;

    // ==================== 挑战者分配器（--allocator=） ====================
    //
    // 主实验原本只跑 CacheAware_v2。要把 CacheAware_v3（AJLR-lite）纳入同一套
    // 统计与门禁，就只能换"挑战者"，不能换基线：WFD 与 SharedCacheModel 一行不动，
    // 这样两代挑战者的数字才可以直接对比。
    //
    //   --allocator=v2 （默认）历史实现，行为与 2026-11 之前逐位一致；
    //   --allocator=v3  AJLR-lite：命中历史 + 负载窗口，参数见 --policy/--alpha/--premium。

    /** 挑战者标识：v2（默认）或 v3。 */
    private static String ALLOCATOR = "v2";

    /** 挑战者在 CSV 的 Algorithm 列与报告里的名字（跟随 ALLOCATOR，不写死）。 */
    private static String CHALLENGER_NAME = "CacheAware_v2";

    /** v3 判定规则：MIN_FINISH 或 HEAD_ROOM（仅 --allocator=v3 生效）。 */
    private static String V3_POLICY = "HEAD_ROOM";

    /** v3 负载窗口 α（仅 --allocator=v3 生效）。 */
    private static double V3_ALPHA = 0.5;

    /** v3 的 CRP 溢价缩放（1.0 = 与模型一致；仅 --allocator=v3 生效）。 */
    private static double V3_PREMIUM = 1.0;

    /**
     * 是否沿用"WFD 静态负载跨用例不清零"的旧口径。
     *
     * <p>默认关闭（每个用例前 {@code WFD.resetLoads()}）。开着它，100 个用例会继承
     * 前 99 个用例累积的负载偏置，而挑战者每用例都复位——这正是 2026-11 复核定位到
     * 的基线占便宜（见 docs/validation/2026-11-13/SUMMARY.md）。保留开关只是为了让
     * 2026-10-24 归档的 −0.954% 仍能逐字节复现。
     */
    private static boolean LEGACY_WFD_STATE = false;
    
    /** 性能分析器实例 */
    private static PerformanceAnalyzer analyzer;
    
    /** 结果可视化器实例 */
    private static ResultVisualizer visualizer;
      /**
     * 主程序入口
     * 
     * 启动WFD vs Cache-Aware算法的全面性能对比实验。
     * 包括系统参数设置、实验执行、结果分析和可视化生成。
     * 
     * @param args 命令行参数（当前未使用）
     */
    /** 打印用法。 */
    private static void printUsage() {
        System.out.println("用法: java AlgorithmComparisonExperiment [选项]");
        System.out.println();
        System.out.println("配置文件（优先级：内置默认 < 配置文件 < 命令行）:");
        System.out.println("  --config=PATH     指定 YAML 配置（不存在会报错，不会静默回落）");
        System.out.println("                    未指定时依次尝试 $YAT_SIM_CONFIG、");
        System.out.println("                    config/default.yaml、simulator/config/default.yaml");
        System.out.println();
        System.out.println("规模与种子:");
        System.out.println("  --cores=N         处理器核心数");
        System.out.println("  --cases=N         总测试案例数");
        System.out.println("  --tasks=N         每案例任务数");
        System.out.println("  --seed=N          单随机种子");
        System.out.println("  --seeds=a,b,c     多随机种子；也可写 --seeds=K 表示 1..K");
        System.out.println();
        System.out.println("挑战者分配器（只换挑战者，不换基线）:");
        System.out.println("  --allocator=v2|v3 挑战者，默认 v2（历史实现）；v3 = AJLR-lite");
        System.out.println("  --policy=NAME     v3 的判定规则：HEAD_ROOM（默认）或 MIN_FINISH");
        System.out.println("  --alpha=R         v3 的负载窗口，默认 0.5；0=只在最轻核里按命中挑");
        System.out.println("  --premium=R       v3 的 CRP 溢价缩放，默认 1.0（用于误差敏感性）");
        System.out.println("  --legacy-wfd-state 沿用 WFD 负载跨用例不清零的旧口径（复现归档数字用）");
        System.out.println();
        System.out.println("度量与输出:");
        System.out.println("  --legacy-metrics  使用旧的（不公平）口径复现历史结果");
        System.out.println("  --no-viz          跳过 Python 可视化（快速/CI 场景）");
        System.out.println("  --print-config    打印最终生效的配置后继续运行");
        System.out.println("  -h, --help        打印本帮助并退出");
    }

    /**
     * 解析命令行参数并装配配置。
     *
     * <p>两趟扫描是刻意的：配置文件必须<b>先于</b>任何覆盖项加载，而 CLI 中
     * {@code --config=} 的位置不应影响其余参数的语义（否则
     * {@code --cores=4 --config=a.yaml} 与 {@code --config=a.yaml --cores=4}
     * 结果不同，这类隐性顺序依赖极难排查）。
     *
     * <p>顺序：
     * <ol>
     *   <li>第一趟：找 {@code -h/--help}（立即退出）与 {@code --config=}</li>
     *   <li>加载配置 → 写入静态字段</li>
     *   <li>第二趟：应用 CLI 覆盖（覆盖项永远赢）</li>
     * </ol>
     */
    private static void parseArgs(String[] args) {
        Path configPath = null;
        for (String arg : args) {
            if (arg.equals("-h") || arg.equals("--help")) {
                printUsage();
                System.exit(0);
            }
            if (arg.startsWith("--config=")) {
                configPath = Paths.get(arg.substring("--config=".length()));
            }
        }

        try {
            CONFIG = ExperimentConfig.discover(configPath);
        } catch (Exception e) {
            System.err.println("❌ 配置加载失败: " + e.getMessage());
            System.exit(2);
        }
        applyConfigToFields();

        boolean printConfig = false;
        for (String arg : args) {
            try {
                if (arg.startsWith("--config=")) {
                    // 已在第一趟处理
                } else if (arg.equals("--no-viz")) {
                    ENABLE_VIZ = false;
                    CONFIG.overrideEnableViz(false);
                } else if (arg.startsWith("--allocator=")) {
                    ALLOCATOR = arg.substring("--allocator=".length()).trim();
                } else if (arg.startsWith("--policy=")) {
                    V3_POLICY = arg.substring("--policy=".length()).trim()
                            .toUpperCase(java.util.Locale.ROOT);
                } else if (arg.startsWith("--alpha=")) {
                    V3_ALPHA = Double.parseDouble(arg.substring("--alpha=".length()));
                } else if (arg.startsWith("--premium=")) {
                    V3_PREMIUM = Double.parseDouble(arg.substring("--premium=".length()));
                } else if (arg.equals("--legacy-wfd-state")) {
                    LEGACY_WFD_STATE = true;
                } else if (arg.equals("--legacy-metrics")) {
                    LEGACY_METRICS = true;
                    CONFIG.overrideLegacyMetrics(true);
                } else if (arg.equals("--print-config")) {
                    printConfig = true;
                } else if (arg.startsWith("--cores=")) {
                    CORES = Integer.parseInt(arg.substring(8));
                    CONFIG.overrideCores(CORES);
                } else if (arg.startsWith("--cases=")) {
                    TOTAL_TEST_CASES = Integer.parseInt(arg.substring(8));
                    CONFIG.overrideTotalTestCases(TOTAL_TEST_CASES);
                } else if (arg.startsWith("--tasks=")) {
                    TASKS_PER_CASE = Integer.parseInt(arg.substring(8));
                    CONFIG.overrideTasksPerCase(TASKS_PER_CASE);
                } else if (arg.startsWith("--seed=")) {
                    SEED = Long.parseLong(arg.substring(7));
                    CONFIG.overrideSeed(SEED);
                } else if (arg.startsWith("--seeds=")) {
                    SEEDS = parseSeeds(arg.substring(8));
                    CONFIG.overrideSeeds(SEEDS);
                } else {
                    System.err.println("Unknown argument: " + arg);
                    System.exit(2);
                }
            } catch (NumberFormatException e) {
                System.err.println("❌ 参数格式错误: " + arg + "（应为 --key=整数）");
                System.exit(2);
            }
        }
        try {
            CONFIG.validate();
        } catch (ExperimentConfig.ConfigException e) {
            System.err.println("Invalid effective configuration: " + e.getMessage());
            System.exit(2);
        }
        applyConfigToFields();
        applyAllocatorSelection();
        if (printConfig) {
            System.out.println("--- 最终生效配置 ---");
            System.out.println(CONFIG.describe());
            System.out.println("--------------------");
            System.out.println();
        }
    }

    /**
     * 把 {@link ExperimentConfig} 的取值同步到本类的运行期字段。
     *
     * <p>单点装配：新增一个可配置项时只需要在这里加一行，不必再回头找散落在
     * 实验主体里的字面量。
     */
    private static void applyConfigToFields() {
        CORES = CONFIG.cores;
        TOTAL_TEST_CASES = CONFIG.totalTestCases;
        TASKS_PER_CASE = CONFIG.tasksPerCase;
        SEED = CONFIG.seed;
        SEEDS = CONFIG.seeds;
        ENABLE_VIZ = CONFIG.enableViz;
        LEGACY_METRICS = CONFIG.legacyMetrics;
        UTILIZATION_LEVELS = CONFIG.utilizationLevels.clone();
        HIGH_CACHE_SENSITIVITY_RATIO = CONFIG.highCacheSensitivityRatio;
    }

    /**
     * 校验并落地挑战者选择。
     *
     * <p>两处硬约束，都是"宁可报错也不静默混口径"：
     * <ul>
     *   <li>{@code --legacy-metrics} 与 {@code --allocator=v3} 互斥：旧口径的执行时间
     *       打折来自 {@code CacheAware_v2.calculateExecutionTime()}，v3 没有这个函数，
     *       放行只会得到两种口径的混合物。</li>
     *   <li>α 必须非负、premium 必须为正；越界直接退出，不夹到边界。</li>
     * </ul>
     */
    private static void applyAllocatorSelection() {
        if ("v2".equalsIgnoreCase(ALLOCATOR)) {
            ALLOCATOR = "v2";
            CHALLENGER_NAME = "CacheAware_v2";
            return;
        }
        if (!"v3".equalsIgnoreCase(ALLOCATOR)) {
            System.err.println("❌ --allocator 只接受 v2 或 v3，收到: " + ALLOCATOR);
            System.exit(2);
        }
        if (!"MIN_FINISH".equals(V3_POLICY) && !"HEAD_ROOM".equals(V3_POLICY)) {
            System.err.println("❌ --policy 只接受 MIN_FINISH 或 HEAD_ROOM，收到: " + V3_POLICY);
            System.exit(2);
        }
        if (LEGACY_METRICS) {
            System.err.println("❌ --legacy-metrics 不能与 --allocator=v3 同时使用：");
            System.err.println("   旧口径的执行时间打折只存在于 CacheAware_v2，v3 没有对应实现。");
            System.exit(2);
        }
        if (!(V3_ALPHA >= 0.0) || Double.isNaN(V3_ALPHA)) {
            System.err.println("❌ --alpha 必须为非负数，收到: " + V3_ALPHA);
            System.exit(2);
        }
        if (!(V3_PREMIUM > 0.0) || Double.isNaN(V3_PREMIUM)) {
            System.err.println("❌ --premium 必须为正数，收到: " + V3_PREMIUM);
            System.exit(2);
        }
        ALLOCATOR = "v3";
        CHALLENGER_NAME = "CacheAware_v3";
        CacheAware_v3.setPolicy(
                "MIN_FINISH".equals(V3_POLICY) ? CacheAware_v3.Policy.MIN_FINISH
                                               : CacheAware_v3.Policy.HEAD_ROOM,
                V3_ALPHA);
        CacheAware_v3.setPremiumScale(V3_PREMIUM);
    }

    /**
     * 解析 --seeds 参数。
     * <ul>
     *   <li>{@code --seeds=1,2,3} → 精确的三个种子</li>
     *   <li>{@code --seeds=5} → 简写，等价于 {@code 1,2,3,4,5}</li>
     * </ul>
     */
    private static long[] parseSeeds(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            throw new NumberFormatException("Seed specification must not be empty");
        }
        String[] parts = spec.split(",", -1);
        if (parts.length == 1 && !parts[0].contains("-")) {
            int count = Integer.parseInt(parts[0].trim());
            if (count <= 0) {
                throw new NumberFormatException("seeds 数量必须为正");
            }
            long[] seeds = new long[count];
            for (int i = 0; i < count; i++) {
                seeds[i] = i + 1L;
            }
            return seeds;
        }
        long[] seeds = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            seeds[i] = Long.parseLong(parts[i].trim());
        }
        return seeds;
    }

    public static void main(String[] args) {
        parseArgs(args);
        System.out.println("=== WFD vs " + CHALLENGER_NAME + " 算法性能对比实验 ===");
        System.out.println("🎯 目标：在**统一执行/缓存模型**下对比两种放置策略（公平基线 C1）");
        System.out.println("📊 实验规模: " + TOTAL_TEST_CASES + " 个测试案例");
        System.out.println("💻 处理器核心数: " + CORES);
        System.out.println("📋 每案例任务数: " + TASKS_PER_CASE);
        System.out.println("⚡ 高缓存敏感任务比例: " + (HIGH_CACHE_SENSITIVITY_RATIO * 100) + "%");
        System.out.println("📈 利用率级别: " + java.util.Arrays.toString(UTILIZATION_LEVELS));
        System.out.println("🧪 度量口径: " + (LEGACY_METRICS
                ? "LEGACY（旧的不公平口径，仅用于对比）"
                : "FAIR（WFD/" + CHALLENGER_NAME + " 共用 SharedCacheModel）"));
        System.out.println("🧹 WFD 基线口径: " + (LEGACY_WFD_STATE
                ? "LEGACY（静态负载跨用例不清零，仅用于复现归档）"
                : "CLEAN（每用例复位 WFD 负载表）"));
        System.out.println("🧩 挑战者分配器: " + CHALLENGER_NAME
                + ("v3".equals(ALLOCATOR)
                   ? String.format("（policy=%s, alpha=%.2f, premium=%.2f）",
                                   CacheAware_v3.getPolicy(), V3_ALPHA, V3_PREMIUM)
                   : ""));
        if (SEEDS != null) {
            System.out.println("🎲 随机种子: " + java.util.Arrays.toString(SEEDS));
        } else {
            System.out.println("🎲 随机种子: " + SEED);
        }
        System.out.println();
        
        // 设置系统参数
        setupSystemParameters();
        
        if (SEEDS == null) {
            // 单种子路径（保持历史行为）
            analyzer = new PerformanceAnalyzer(LEGACY_METRICS);
            visualizer = new ResultVisualizer();
            runComparison(SEED, true);
        } else {
            runMultiSeed();
        }
        
        System.out.println("=== 实验完成 ===");
    }

    /**
     * 多种子实验：每个种子独立跑一遍，然后汇总均值 / 标准差 / 95% 置信区间。
     *
     * <p>公平性不只要"口径一致"，还要能说明差异不是某个种子挑出来的。
     */
    private static void runMultiSeed() {
        int n = SEEDS.length;
        double[] makespanImp = new double[n];
        double[] hitImp = new double[n];
        double[] winRate = new double[n];

        for (int i = 0; i < n; i++) {
            System.out.println("\n" + "-".repeat(70));
            System.out.printf("🎲 种子 %d/%d: %d%n", i + 1, n, SEEDS[i]);
            System.out.println("-".repeat(70));

            analyzer = new PerformanceAnalyzer(LEGACY_METRICS);
            visualizer = new ResultVisualizer();
            SeedSummary s = runComparison(SEEDS[i], false);
            makespanImp[i] = s.makespanImprovementPct;
            hitImp[i] = s.cacheHitImprovementPct;
            winRate[i] = s.cacheAwareWinRatePct;
        }

        System.out.println("\n" + "=".repeat(80));
        System.out.println("📊 多种子汇总（公平基线 C1）");
        System.out.println("=".repeat(80));
        printAggregate("Makespan 改进 (%)", makespanImp);
        printAggregate("缓存命中率提升 (百分点)", hitImp);
        printAggregate(CHALLENGER_NAME + " 胜率 (%)", winRate);
        System.out.println("=".repeat(80));
    }

    /** 打印一组样本的均值、样本标准差与 95% 置信区间半宽。 */
    private static void printAggregate(String label, double[] xs) {
        int n = xs.length;
        double mean = 0;
        for (double x : xs) {
            mean += x;
        }
        mean /= n;

        double variance = 0;
        if (n > 1) {
            for (double x : xs) {
                variance += (x - mean) * (x - mean);
            }
            variance /= (n - 1);
        }
        double sd = Math.sqrt(variance);
        double t = n > 1 ? ConfidenceInterval.critical95(n) : Double.NaN;
        double ci = ConfidenceInterval.halfWidth95(xs);

        if (n < 2) {
            System.out.printf("   %s 均值=%.3f  标准差及95%%CI不可估计 (n=%d)%n", label, mean, n);
        } else {
            System.out.printf("   %-28s 均值=%8.3f  标准差=%7.3f  95%%CI=±%.3f  (n=%d, t=%.3f)%n",
                    label, mean, sd, ci, n, t);
        }
        System.out.print("      样本: [");
        for (int i = 0; i < n; i++) {
            System.out.printf("%s%.2f", i == 0 ? "" : ", ", xs[i]);
        }
        System.out.println("]");
    }

    /** 单次运行的汇总数据。 */
    private static final class SeedSummary {
        double makespanImprovementPct;
        double cacheHitImprovementPct;
        double cacheAwareWinRatePct;
    }
      /**
     * 设置系统参数配置
     * 
     * 为Cache-Aware算法优化系统参数，重点配置缓存层次结构和权重分配。
     * 这些参数直接影响算法的性能表现和缓存命中率计算。
     */
    private static void setupSystemParameters() {
        // 单点写入：全部取值来自 CONFIG（config/*.yaml 或内置默认），
        // 这里不再保留任何魔法数字，避免"配置改了这一处、别处还有一份"。
        CONFIG.applyToSystemParameters();

        System.out.println("🔧 系统参数配置:");
        System.out.println("   缓存权重: L1=" + SystemParameters.cc_weights[0]
                + " L2=" + SystemParameters.cc_weights[1]
                + " L3=" + SystemParameters.cc_weights[2]
                + " Mem=" + SystemParameters.cc_weights[3]);
        System.out.println("   L2缓存共享: 每" + SystemParameters.Level2CoreNum + "个核心共享");
        System.out.println("   缓存层级数: " + SystemParameters.cacheLevel);
        System.out.println();
    }
      /**
     * 运行算法对比实验主流程
     * 
     * 该方法是整个实验的核心，负责：
     * 1. 循环执行多个利用率级别的测试
     * 2. 为每个测试案例生成缓存敏感的任务集
     * 3. 分别执行WFD和Cache-Aware算法
     * 4. 收集和分析性能数据
     * 5. 生成实时进度报告
     * 6. 统计胜负情况和性能改进
     * 7. 生成最终分析报告和可视化
     */
    private static SeedSummary runComparison(long seed, boolean withViz) {
        SeedSummary summary = new SeedSummary();
        System.out.println("🚀 开始 WFD vs " + CHALLENGER_NAME + " 对比实验...\n");

        // 旧口径下才允许 CacheAware 把自估命中率写回任务；公平基线永不写回。
        CacheAware_v2.setLegacyWriteBack(LEGACY_METRICS);

        // 初始化随机数生成器和任务生成器
        Random rng = new Random(seed); // 固定种子确保结果可重复（可由 --seed=/--seeds= 覆盖）
        EnhancedTaskGenerator taskGenerator = new EnhancedTaskGenerator(CORES, rng);
        
        // 存储各算法的实验结果
        Map<String, List<ExperimentResult>> algorithmResults = new HashMap<>();
        algorithmResults.put("WFD", new ArrayList<>());
        algorithmResults.put(CHALLENGER_NAME, new ArrayList<>());
        
        // 性能统计变量
        int completedTests = 0;
        int wfdWins = 0;
        int cacheAwareWins = 0;
        double totalMakespanImprovement = 0.0;
        double totalCacheHitImprovement = 0.0;
        
        // 对每个利用率级别进行测试
        // 将总案例数尽量均匀分配到各利用率级别（修正整数除法导致案例被丢弃的问题）
        int levels = UTILIZATION_LEVELS.length;
        int baseTestsPerLevel = TOTAL_TEST_CASES / levels;
        int remainder = TOTAL_TEST_CASES % levels;
        for (int levelIdx = 0; levelIdx < levels; levelIdx++) {
            double utilization = UTILIZATION_LEVELS[levelIdx];
            // 前 remainder 个级别各多分 1 个案例，保证总数精确等于 TOTAL_TEST_CASES
            int testsPerLevel = baseTestsPerLevel + (levelIdx < remainder ? 1 : 0);
            testsPerLevel = Math.max(1, testsPerLevel);
            
            System.out.println("📊 测试利用率级别: " + utilization);
            
            for (int test = 0; test < testsPerLevel; test++) {
                completedTests++;
                
                // 生成缓存敏感的任务集
                List<Node> originalTasks = taskGenerator.generateCacheIntensiveTasks(
                    TASKS_PER_CASE, utilization, HIGH_CACHE_SENSITIVITY_RATIO
                );
                
                // === WFD算法测试 ===
                // 每个用例前清空静态负载表：test case 之间相互独立，基线不该继承
                // 前一个用例的负载（--legacy-wfd-state 可恢复旧口径以复现归档）。
                if (!LEGACY_WFD_STATE) {
                    WFD.resetLoads();
                }
                List<Node> wfdTasks = cloneTasks(originalTasks);
                List<Integer> wfdProcessors = generateProcessorList(CORES);
                WFD wfdAlgorithm = new WFD();
                
                long wfdStartTime = System.nanoTime();
                simulateWFDAllocation(wfdTasks, wfdProcessors, wfdAlgorithm);
                long wfdEndTime = System.nanoTime();
                  ExperimentResult wfdResult = analyzer.analyzeAlgorithmPerformance(
                    "WFD", completedTests, wfdTasks, CORES, 
                    (wfdEndTime - wfdStartTime) / 1_000_000.0, utilization
                );
                algorithmResults.get("WFD").add(wfdResult);
                
                // === 挑战者算法测试（v2 或 v3，见 --allocator=） ===
                List<Node> cacheTasks = cloneTasks(originalTasks);
                List<Integer> cacheProcessors = generateProcessorList(CORES);
                // 每个用例新建实例：v3 的命中历史必须随用例清零，否则就是本轮在
                // WFD 静态负载表上发现的那类状态泄漏（docs/validation/2026-11-13）。
                AllocationMethods cacheAlgorithm = newChallenger();
                
                // 重置缓存状态（v2 的静态状态；v3 由新实例保证）
                CacheAware_v2.resetState();
                
                long cacheStartTime = System.nanoTime();
                simulateCacheAwareAllocation(cacheTasks, cacheProcessors, cacheAlgorithm);
                long cacheEndTime = System.nanoTime();
                
                ExperimentResult cacheResult = analyzer.analyzeAlgorithmPerformance(
                    CHALLENGER_NAME, completedTests, cacheTasks, CORES,
                    (cacheEndTime - cacheStartTime) / 1_000_000.0, utilization
                );
                algorithmResults.get(CHALLENGER_NAME).add(cacheResult);
                
                // 计算性能差异
                double makespanImprovement = wfdResult.makespan > 0
                        ? (wfdResult.makespan - cacheResult.makespan) / wfdResult.makespan
                        : 0.0;
                double cacheHitImprovement = cacheResult.cacheHitRatio - wfdResult.cacheHitRatio;
                
                totalMakespanImprovement += makespanImprovement;
                totalCacheHitImprovement += cacheHitImprovement;
                
                // 胜负统计
                if (cacheResult.makespan < wfdResult.makespan) {
                    cacheAwareWins++;
                } else {
                    wfdWins++;
                }
                
                // 详细进度报告（每10个案例）
                if (completedTests % 10 == 0) {
                    System.out.printf("✅ 完成 %d/%d 测试案例\n", completedTests, TOTAL_TEST_CASES);
                    System.out.printf("   当前makespan改进: %.2f%%\n", makespanImprovement * 100);
                    System.out.printf("   当前缓存命中率改进: %.3f\n", cacheHitImprovement);
                    System.out.printf("   %s 胜率: %.1f%%\n", CHALLENGER_NAME,
                                     (double)cacheAwareWins / completedTests * 100);

                    // 缓存状态快照只有 v2 提供；v3 的历史表按用例重建，在这里打印
                    // 只会给出"已经空了"的误导性快照，故跳过。
                    if ("v2".equals(ALLOCATOR)) {
                        System.out.println("   🗄️ 缓存状态快照:");
                        Map<Integer, String> cacheReport = CacheAware_v2.getCacheStateReport();
                        for (Map.Entry<Integer, String> entry : cacheReport.entrySet()) {
                            System.out.println("     " + entry.getValue());
                        }
                        System.out.println();
                    }
                }
            }
        }
        
        // 生成详细的分析报告
        generateReport(algorithmResults, cacheAwareWins, wfdWins, completedTests,
                      totalMakespanImprovement, totalCacheHitImprovement);
        
        // Numeric artifacts are required even when rendering is disabled.
        if (!withViz || !ENABLE_VIZ) {
            File resultDir = new File("result");
            if (!resultDir.isDirectory() && !resultDir.mkdirs()) {
                throw new IllegalStateException("Cannot create result directory");
            }
            String csv = withViz ? "result/algorithm_comparison_results.csv"
                    : "result/algorithm_comparison_seed_" + seed + ".csv";
            visualizer.exportToCSV(algorithmResults, csv);
        }
        // 生成可视化图表（快速/CI 场景可用 --no-viz 跳过）
        if (withViz && ENABLE_VIZ) {
            generateVisualization(algorithmResults);
        } else if (!withViz) {
            System.out.println("ℹ️  多种子模式：跳过可视化（仅做统计汇总）");
        } else {
            System.out.println("ℹ️  已通过 --no-viz 跳过可视化生成");
        }
        
        // 重置算法状态
        WFD.resetLoads();
        CacheAware_v2.resetState();
        
        summary.makespanImprovementPct = (totalMakespanImprovement / completedTests) * 100.0;
        summary.cacheHitImprovementPct = (totalCacheHitImprovement / completedTests) * 100.0;
        summary.cacheAwareWinRatePct = (double) cacheAwareWins / completedTests * 100.0;
        return summary;
    }

    /**
     * 模拟Cache-Aware算法的任务分配过程
     * 
     * 该方法模拟Cache-Aware算法的完整执行过程，包括：
     * 1. 任务分配决策
     * 2. 缓存感知的执行时间计算
     * 3. 处理器负载更新
     * 4. 任务调度时间计算
     * 
     * @param tasks 要分配的任务列表
     * @param processors 可用处理器列表
     * @param algorithm Cache-Aware算法实例
     */
    private static AllocationMethods newChallenger() {
        if ("v3".equals(ALLOCATOR)) {
            return new CacheAware_v3(CORES, SystemParameters.Level2CoreNum);
        }
        return new CacheAware_v2();
    }

    private static void simulateCacheAwareAllocation(List<Node> tasks,
            List<Integer> processors, AllocationMethods algorithm) {
        
        // 共享执行/缓存模型：与 WFD 完全同一套口径（公平基线 C1）
        SharedCacheModel cacheModel = new SharedCacheModel(
                processors.size(), SystemParameters.Level2CoreNum);
        
        // 处理器负载跟踪数组
        long[] processorLoads = new long[processors.size()];
        
        // 逐个处理任务
        for (Node task : tasks) {
            // 准备就绪任务列表（Cache-Aware算法每次处理一个任务）
            List<Node> readyTasks = new ArrayList<>();
            readyTasks.add(task);
            
            // 调用Cache-Aware算法进行任务分配（**唯一的**差异点：选哪个处理器）
            int allocatedProcessor = algorithm.allocate(readyTasks, processors);
            
            if (allocatedProcessor != -1) {
                // 记录任务分配结果
                task.partition = allocatedProcessor;
                
                // 由共享模型统一计算执行时间与缓存命中（不修改 expectedET）
                long et = LEGACY_METRICS
                        ? legacyCacheAwareET(task, allocatedProcessor)
                        : cacheModel.execute(task, allocatedProcessor);
                
                // 计算任务的调度时间
                task.start = processorLoads[allocatedProcessor];
                task.finishAt = task.start + et;
                processorLoads[allocatedProcessor] = task.finishAt;
                task.finish = true;
            }
        }
    }

    /**
     * 旧口径（仅 --legacy-metrics 复现历史结果时使用）：用 v2 内部估计模型算执行时间。
     */
    private static long legacyCacheAwareET(Node task, int processor) {
        long original = task.expectedET;
        long et = CacheAware_v2.calculateExecutionTime(task, processor);
        task.expectedET = original; // 即使旧口径也不覆盖基准时间
        return et;
    }

    /**
     * 模拟WFD算法的任务分配过程
     * 
     * 该方法模拟传统WFD算法的执行过程，作为对比基准：
     * 1. 基于负载均衡的简单分配策略
     * 2. 使用原始执行时间（无缓存优化）
     * 3. 处理器负载更新
     * 4. 任务调度时间计算
     * 
     * @param tasks 要分配的任务列表
     * @param processors 可用处理器列表  
     * @param algorithm WFD算法实例
     */
    private static void simulateWFDAllocation(List<Node> tasks, List<Integer> processors, WFD algorithm) {
        // 共享执行/缓存模型：与 CacheAware 完全同一套口径（公平基线 C1）
        SharedCacheModel cacheModel = new SharedCacheModel(
                processors.size(), SystemParameters.Level2CoreNum);
        
        // 处理器负载跟踪数组
        long[] processorLoads = new long[processors.size()];
        
        // 逐个处理任务
        for (Node task : tasks) {
            // 准备就绪任务列表（WFD算法每次处理一个任务）
            List<Node> readyTasks = new ArrayList<>();
            readyTasks.add(task);
            
            // 调用WFD算法进行任务分配（**唯一的**差异点：选哪个处理器）
            int allocatedProcessor = algorithm.allocate(readyTasks, processors);
            
            if (allocatedProcessor != -1) {
                // 记录任务分配结果
                task.partition = allocatedProcessor;
                
                // 由共享模型统一计算执行时间与缓存命中。
                // 注意：不再直接使用未经缓存建模的 expectedET，也不覆盖它。
                long executionTime = LEGACY_METRICS
                        ? task.expectedET
                        : cacheModel.execute(task, allocatedProcessor);
                
                // 计算任务的调度时间
                task.start = processorLoads[allocatedProcessor];
                task.finishAt = task.start + executionTime;
                processorLoads[allocatedProcessor] = task.finishAt;
                task.finish = true;
            }
        }
    }
      /**
     * 克隆任务列表（深拷贝）
     */    private static List<Node> cloneTasks(List<Node> original) {
        List<Node> cloned = new ArrayList<>();
        Random rng = new Random(42); // 用于创建Node的随机数生成器
        
        for (Node node : original) {
            // 使用Node的正确构造函数
            Node clonedNode = new Node(node.getLayer(), node.getType(), node.getId(), 
                                     node.getDagID(), node.crp, rng);
            
            // 复制关键属性
            clonedNode.expectedET = node.expectedET;
            clonedNode.sensitivity = node.sensitivity;
            clonedNode.affinity = node.affinity;
            
            if (node.weights != null) {
                clonedNode.weights = node.weights.clone();
            }
            
            cloned.add(clonedNode);
        }
        return cloned;
    }
    
    /**
     * 生成处理器列表
     */
    private static List<Integer> generateProcessorList(int cores) {
        List<Integer> processors = new ArrayList<>();
        for (int i = 0; i < cores; i++) {
            processors.add(i);
        }
        return processors;
    }
    
    /**
     * 生成详细的分析报告
     */
    private static void generateReport(Map<String, List<ExperimentResult>> algorithmResults, 
            int cacheAwareWins, int wfdWins, int totalTests,
            double totalMakespanImprovement, double totalCacheHitImprovement) {
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("📈 WFD vs " + CHALLENGER_NAME + " 实验结果详细分析报告");
        System.out.println("=".repeat(80));
        
        // 基础统计
        double avgMakespanImprovement = (totalMakespanImprovement / totalTests) * 100;
        double avgCacheHitImprovement = (totalCacheHitImprovement / totalTests) * 100;
        double winRate = (double)cacheAwareWins / totalTests * 100;
        
        System.out.println("🏆 总体性能对比:");
        System.out.printf("   📊 测试案例总数: %d\n", totalTests);
        // 注意：这里是「逐用例相对改进的配对均值」，不是 (均值之差 / 均值) 的比值口径
        System.out.printf("   ⏱️  平均Makespan改进（配对均值）: %.2f%%\n", avgMakespanImprovement);
        // 命中率差是「百分点」而非相对百分比，单位不可写成 %
        System.out.printf("   🎯 平均缓存命中率改进: %.2f 个百分点\n", avgCacheHitImprovement);
        System.out.printf("   🏅 %s 胜率: %d/%d = %.1f%%\n", CHALLENGER_NAME,
                          cacheAwareWins, totalTests, winRate);
        
        // 详细统计分析
        List<ExperimentResult> wfdResults = algorithmResults.get("WFD");
        List<ExperimentResult> cacheResults = algorithmResults.get(CHALLENGER_NAME);
          // 计算统计指标
        double wfdAvgMakespan = wfdResults.stream().mapToDouble(r -> r.makespan).average().orElse(0);
        double cacheAvgMakespan = cacheResults.stream().mapToDouble(r -> r.makespan).average().orElse(0);
        double wfdAvgCacheHit = wfdResults.stream().mapToDouble(r -> r.cacheHitRatio).average().orElse(0);
        double cacheAvgCacheHit = cacheResults.stream().mapToDouble(r -> r.cacheHitRatio).average().orElse(0);
        
        System.out.println("\n📋 详细性能指标:");
        System.out.printf("   WFD算法:\n");
        System.out.printf("     - 平均Makespan: %.0f ns\n", wfdAvgMakespan);
        System.out.printf("     - 平均缓存命中率: %.3f (%.1f%%)\n", wfdAvgCacheHit, wfdAvgCacheHit * 100);
        
        System.out.printf("   %s 算法:\n", CHALLENGER_NAME);
        System.out.printf("     - 平均Makespan: %.0f ns\n", cacheAvgMakespan);
        System.out.printf("     - 平均缓存命中率: %.3f (%.1f%%)\n", cacheAvgCacheHit, cacheAvgCacheHit * 100);
        
        System.out.printf("   性能改进:\n");
        // 比值口径（均值之差 / WFD 均值）——便于直觉，但会被大用例主导
        System.out.printf("     - Makespan减少（比值口径）: %.0f ns (%.2f%%)\n",
                          wfdAvgMakespan - cacheAvgMakespan,
                          ((wfdAvgMakespan - cacheAvgMakespan) / wfdAvgMakespan) * 100);
        // 配对口径（逐用例相对改进的均值）——统计检验应用这个
        System.out.printf("     - Makespan改进（配对均值，用于显著性检验）: %.2f%%\n",
                          avgMakespanImprovement);
        System.out.printf("     - 缓存命中率提升: %.3f (%.1f个百分点)\n", 
                          cacheAvgCacheHit - wfdAvgCacheHit,
                          (cacheAvgCacheHit - wfdAvgCacheHit) * 100);
        
        // 结果评估
        System.out.println("\n🔍 结果评估:");
        if (totalTests < 20) {
            System.out.printf("   ℹ️  样本量 n=%d < 20，均值与胜率的方差很大，"
                    + "该行结论仅作冒烟提示，不作为算法结论\n", totalTests);
        }
        System.out.println("   上述改善幅度与胜率为描述性统计，不代表统计显著性或系统稳定性。");
        System.out.println("   请结合独立种子置信区间、工作负载范围与重复测量解释结果。");

        System.out.println("=".repeat(80));
    }
    
    /**
     * 生成可视化图表
     */    /**
     * 生成可视化图表和数据分析
     */
    private static void generateVisualization(Map<String, List<ExperimentResult>> algorithmResults) {
        System.out.println("\n📊 生成可视化图表和数据分析...");
          try {
            // 确保result目录存在
            File resultDir = new File("result");
            if (!resultDir.exists()) {
                resultDir.mkdirs();
                System.out.println("📁 创建结果目录: result");
            }
            
            // 生成ASCII图表
            visualizer.generatePerformanceChart(algorithmResults);
            
            // 导出CSV数据到result目录
            String csvFilename = "result/algorithm_comparison_results.csv";
            visualizer.exportToCSV(algorithmResults, csvFilename);
            System.out.println("✅ 实验数据已导出到: " + csvFilename);
            
            // 生成对比矩阵
            visualizer.generateComparisonMatrix(algorithmResults);
            
            // 调用Python脚本处理CSV数据
            runPythonVisualization(csvFilename);
            
        } catch (java.io.UncheckedIOException e) {
            throw e; // Required numeric artifacts must not be reported as successful.
        } catch (Exception e) {
            System.err.println("❌ 可视化生成失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 调用Python脚本处理CSV数据并生成高级可视化
     */
    private static void runPythonVisualization(String csvPath) {
        System.out.println("\n🐍 启动Python数据可视化处理...");
        
        try {
            // 检查Python环境：优先 python3，回退 python（修正原逻辑把成功命令选反的缺陷）
            String pythonCommand = null;
            for (String candidate : new String[]{"python3", "python"}) {
                try {
                    Process checkProcess =
                            new ProcessBuilder(candidate, "--version").directory(new File(".")).start();
                    if (checkProcess.waitFor() == 0) {
                        pythonCommand = candidate;
                        break;
                    }
                } catch (Exception ignore) {
                    // 该候选命令不存在，继续尝试下一个
                }
            }

            if (pythonCommand == null) {
                System.out.println("⚠️  未找到 python3/python，跳过Python可视化（不影响实验结果）");
                return;
            }

            // 运行Python可视化脚本；继承标准错误以便观察脚本报错
            ProcessBuilder pb = new ProcessBuilder(pythonCommand, "visualizer/visualize.py", csvPath);
            pb.directory(new File("."));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // 实时转发脚本输出（避免静默失败导致“无图表”难以察觉）
            try (java.io.BufferedReader reader =
                         new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("   [viz] " + line);
                }
            }
            int result = process.waitFor();
            if (result != 0) {
                System.err.println("❌ Python可视化脚本以非零码退出: " + result
                        + "（CSV 已生成，可手动重跑）");
            }
        } catch (Exception e) {
            System.err.println("❌ Python可视化处理失败: " + e.getMessage());
            System.out.println("💡 可以手动运行: python3 visualizer/visualize.py " + csvPath);
        }
    }
}
