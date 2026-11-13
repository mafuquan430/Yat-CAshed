import allocation.CacheAware_v2;
import allocation.CacheAware_v3;
import allocation.WFD;
import analyzer.ConfidenceInterval;
import analyzer.PerformanceAnalyzer;
import analyzer.PerformanceAnalyzer.ExperimentResult;
import entity.Node;
import entity.SharedCacheModel;
import generator.EnhancedTaskGenerator;
import parameters.ExperimentConfig;
import parameters.SystemParameters;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * WFD / CacheAware_v2 / CacheAware_v3（AJLR-lite）三方对照实验。
 *
 * <h3>为什么单独开一个入口，而不是塞进 AlgorithmComparisonExperiment</h3>
 * 主实验的 CSV 与统计口径已经归档（docs/validation/2026-10-24），改它会动到
 * 既有证据链。本类只在<b>完全相同的任务集、完全相同的时间/缓存模型</b>上
 * 增加第三种分配策略，输出独立文件，主实验一行不动。
 *
 * <h3>三方可比性</h3>
 * 同一个用例内三次分配共用同一份 {@code originalTasks}，各自深拷贝后
 * 各自新建 {@code SharedCacheModel}；执行时间与命中率只由
 * {@code SharedCacheModel.execute()} 决定。三者唯一的差别是"把任务放到哪个核"。
 *
 * 用法：
 * <pre>
 *   java -cp "classes:lib/*:." AjlrLiteExperiment --seeds=1,2,3,4,5 --cases=100 --tasks=60
 *   java -cp "classes:lib/*:." AjlrLiteExperiment --seeds=1 --premium=0.5   # 预测误差敏感性
 * </pre>
 */
public class AjlrLiteExperiment {

    private static final String[] CSV_COLUMNS = {
        "Algorithm", "TestCase", "Makespan", "CacheHit", "TotalExecutionTime",
        "AvgExecutionTime", "ResponseTime", "CpuUtilization", "LoadBalance"
    };
    private static final String CSV_FORMAT =
        "%s,%d,%.2f,%.3f,%.2f,%.2f,%.2f,%.3f,%.3f%n";

    private static final String ALG_WFD = "WFD";
    private static final String ALG_V2 = "CacheAware_v2";
    private static final String ALG_V3 = "CacheAware_v3";

    private static int cores = ExperimentConfig.DEFAULT_CORES;
    private static int cases = ExperimentConfig.DEFAULT_TOTAL_TEST_CASES;
    private static int tasksPerCase = ExperimentConfig.DEFAULT_TASKS_PER_CASE;
    private static long[] seeds = {1, 2, 3, 4, 5};
    private static double premium = 1.0;
    private static CacheAware_v3.Policy policy = CacheAware_v3.Policy.HEAD_ROOM;
    private static double alpha = 1.0;
    private static boolean legacyWfdState = false;
    private static String outDir = "result";
    private static double highSensitivityRatio =
            ExperimentConfig.DEFAULT_HIGH_CACHE_SENSITIVITY_RATIO;
    private static double[] levels = ExperimentConfig.DEFAULT_UTILIZATION_LEVELS.clone();

    public static void main(String[] args) throws IOException {
        parse(args);
        SystemParameters.coreNum = cores;
        SystemParameters.Level2CoreNum = ExperimentConfig.DEFAULT_LEVEL2_CORE_NUM;
        SystemParameters.cc_weights = ExperimentConfig.DEFAULT_CC_WEIGHTS.clone();
        CacheAware_v3.setPremiumScale(premium);
        CacheAware_v3.setPolicy(policy, alpha);

        Files.createDirectories(Paths.get(outDir));

        System.out.println("=== WFD vs CacheAware_v2 vs CacheAware_v3 (AJLR-lite) ===");
        System.out.printf(Locale.ROOT,
                "cores=%d cases=%d/seed tasks=%d seeds=%d premium=%.2f levels=%s%n",
                cores, cases, tasksPerCase, seeds.length, premium,
                java.util.Arrays.toString(levels));
        System.out.printf(Locale.ROOT, "v3 预测系数 = %s%n",
                java.util.Arrays.toString(CacheAware_v3.getAssumedFactors()));
        System.out.println();

        double[] v2Mean = new double[seeds.length];
        double[] v3Mean = new double[seeds.length];
        double[] v2Hit = new double[seeds.length];
        double[] v3Hit = new double[seeds.length];
        double[] v2Win = new double[seeds.length];
        double[] v3Win = new double[seeds.length];
        double[] v3Resp = new double[seeds.length];

        for (int s = 0; s < seeds.length; s++) {
            SeedResult r = runSeed(seeds[s]);
            v2Mean[s] = r.v2Imp;
            v3Mean[s] = r.v3Imp;
            v2Hit[s] = r.v2Hit;
            v3Hit[s] = r.v3Hit;
            v2Win[s] = r.v2Win;
            v3Win[s] = r.v3Win;
            v3Resp[s] = r.v3Resp;
            System.out.printf(Locale.ROOT,
                    "seed %-3d  makespan: v2 %+6.2f%%  v3 %+6.2f%%   hit: v2 %+5.2fpp v3 %+5.2fpp"
                            + "   win: v2 %4.0f%% v3 %4.0f%%%n",
                    seeds[s], r.v2Imp, r.v3Imp, r.v2Hit, r.v3Hit, r.v2Win, r.v3Win);
        }

        System.out.println();
        report("CacheAware_v2 vs WFD: Makespan 改进 (%)", v2Mean);
        report("CacheAware_v3 vs WFD: Makespan 改进 (%)", v3Mean);
        report("CacheAware_v2 vs WFD: 命中率差 (pp)", v2Hit);
        report("CacheAware_v3 vs WFD: 命中率差 (pp)", v3Hit);
        report("CacheAware_v3 vs WFD: 响应时间变化 (%)", v3Resp);

        System.out.printf(Locale.ROOT, "%n三方差值（v3 - v2）的配对均值：%.2f 个百分点%n",
                mean(v3Mean) - mean(v2Mean));
    }

    private static void report(String label, double[] xs) {
        double m = mean(xs);
        if (xs.length < 2) {
            System.out.printf(Locale.ROOT, "  %-46s 均值=%8.3f  (n=1，区间不可估计)%n", label, m);
            return;
        }
        double sd = 0.0;
        for (double x : xs) {
            sd += (x - m) * (x - m);
        }
        sd = Math.sqrt(sd / (xs.length - 1));
        double half = ConfidenceInterval.halfWidth95(xs);
        System.out.printf(Locale.ROOT,
                "  %-46s 均值=%8.3f  标准差=%6.3f  95%%CI=±%.3f  [%.3f, %.3f]%n",
                label, m, sd, half, m - half, m + half);
    }

    private static double mean(double[] xs) {
        double sum = 0.0;
        for (double x : xs) {
            sum += x;
        }
        return sum / xs.length;
    }

    private static final class SeedResult {
        double v2Imp, v3Imp, v2Hit, v3Hit, v2Win, v3Win, v3Resp;
    }

    private static SeedResult runSeed(long seed) throws IOException {
        /* 与 AlgorithmComparisonExperiment 对齐：它在每个种子结束时清一次 WFD 静态负载。
         * --legacy-wfd-state 只去掉"每用例一次"的清理，保留"每种子一次"。 */
        WFD.resetLoads();
        Random rng = new Random(seed);
        EnhancedTaskGenerator generator = new EnhancedTaskGenerator(cores, rng);
        PerformanceAnalyzer analyzer = new PerformanceAnalyzer(false);

        Map<String, List<ExperimentResult>> results = new LinkedHashMap<>();
        results.put(ALG_WFD, new ArrayList<>());
        results.put(ALG_V2, new ArrayList<>());
        results.put(ALG_V3, new ArrayList<>());

        double sumV2 = 0.0, sumV3 = 0.0, sumV2Hit = 0.0, sumV3Hit = 0.0, sumV3Resp = 0.0;
        int winsV2 = 0, winsV3 = 0;
        int done = 0;

        int levelCount = levels.length;
        int base = cases / levelCount;
        int rem = cases % levelCount;

        for (int li = 0; li < levelCount; li++) {
            double utilization = levels[li];
            int perLevel = Math.max(1, base + (li < rem ? 1 : 0));
            for (int t = 0; t < perLevel; t++) {
                done++;
                List<Node> original = generator.generateCacheIntensiveTasks(
                        tasksPerCase, utilization, highSensitivityRatio);

                ExperimentResult rWfd = runOne(original, ALG_WFD, done, utilization, analyzer);
                ExperimentResult rV2 = runOne(original, ALG_V2, done, utilization, analyzer);
                ExperimentResult rV3 = runOne(original, ALG_V3, done, utilization, analyzer);

                results.get(ALG_WFD).add(rWfd);
                results.get(ALG_V2).add(rV2);
                results.get(ALG_V3).add(rV3);

                sumV2 += rel(rWfd.makespan, rV2.makespan);
                sumV3 += rel(rWfd.makespan, rV3.makespan);
                sumV2Hit += rV2.cacheHitRatio - rWfd.cacheHitRatio;
                sumV3Hit += rV3.cacheHitRatio - rWfd.cacheHitRatio;
                sumV3Resp += rel(rWfd.averageResponseTime, rV3.averageResponseTime);
                if (rV2.makespan < rWfd.makespan) {
                    winsV2++;
                }
                if (rV3.makespan < rWfd.makespan) {
                    winsV3++;
                }
            }
        }

        String csv = Paths.get(outDir, "ajlr_lite_seed_" + seed + ".csv").toString();
        writeCsv(results, csv);

        SeedResult r = new SeedResult();
        r.v2Imp = sumV2 / done * 100.0;
        r.v3Imp = sumV3 / done * 100.0;
        r.v2Hit = sumV2Hit / done * 100.0;
        r.v3Hit = sumV3Hit / done * 100.0;
        r.v2Win = (double) winsV2 / done * 100.0;
        r.v3Win = (double) winsV3 / done * 100.0;
        r.v3Resp = sumV3Resp / done * 100.0;
        return r;
    }

    /** (base - candidate) / base，base 为 0 时返回 0。 */
    private static double rel(double base, double candidate) {
        return base > 0 ? (base - candidate) / base : 0.0;
    }

    private static ExperimentResult runOne(List<Node> original, String algorithm,
            int testCaseId, double utilization, PerformanceAnalyzer analyzer) {
        List<Node> tasks = cloneTasks(original);
        List<Integer> processors = new ArrayList<>();
        for (int i = 0; i < cores; i++) {
            processors.add(i);
        }

        SharedCacheModel model = new SharedCacheModel(cores, SystemParameters.Level2CoreNum);
        long[] loads = new long[cores];

        AllocationMethodsHolder holder = new AllocationMethodsHolder(algorithm, cores);
        for (Node task : tasks) {
            List<Node> ready = new ArrayList<>(1);
            ready.add(task);
            int cpu = holder.allocate(ready, processors);
            if (cpu < 0) {
                continue;
            }
            task.partition = cpu;
            long et = model.execute(task, cpu);
            task.start = loads[cpu];
            task.finishAt = task.start + et;
            loads[cpu] = task.finishAt;
            task.finish = true;
        }
        return analyzer.analyzeAlgorithmPerformance(
                algorithm, testCaseId, tasks, cores, 0.0, utilization);
    }

    /** 每次重建策略实例，避免跨用例串状态。 */
    private static final class AllocationMethodsHolder {
        private final allocation.AllocationMethods algorithm;

        AllocationMethodsHolder(String name, int cores) {
            if (ALG_WFD.equals(name)) {
                if (!legacyWfdState) {
                    WFD.resetLoads();
                }
                this.algorithm = new WFD();
            } else if (ALG_V2.equals(name)) {
                CacheAware_v2.resetState();
                this.algorithm = new CacheAware_v2();
            } else if (ALG_V3.equals(name)) {
                this.algorithm = new CacheAware_v3(cores, SystemParameters.Level2CoreNum);
            } else {
                throw new IllegalArgumentException("未知算法: " + name);
            }
        }

        int allocate(List<Node> ready, List<Integer> processors) {
            return algorithm.allocate(ready, processors);
        }
    }

    private static List<Node> cloneTasks(List<Node> original) {
        List<Node> cloned = new ArrayList<>(original.size());
        Random rng = new Random(42);
        for (Node node : original) {
            Node copy = new Node(node.getLayer(), node.getType(), node.getId(),
                    node.getDagID(), node.crp, rng);
            copy.expectedET = node.expectedET;
            copy.sensitivity = node.sensitivity;
            copy.affinity = node.affinity;
            copy.hasFaults = node.hasFaults;
            if (node.weights != null) {
                copy.weights = node.weights.clone();
            }
            cloned.add(copy);
        }
        return cloned;
    }

    private static void writeCsv(Map<String, List<ExperimentResult>> results, String path)
            throws IOException {
        File file = new File(path);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            writer.write(String.join(",", CSV_COLUMNS));
            writer.write('\n');
            for (Map.Entry<String, List<ExperimentResult>> e : results.entrySet()) {
                for (ExperimentResult r : e.getValue()) {
                    writer.write(String.format(Locale.ROOT, CSV_FORMAT,
                            e.getKey(), r.testCaseId, r.makespan, r.cacheHitRatio,
                            r.totalTaskExecutionTime, r.averageTaskExecutionTime,
                            r.averageResponseTime, r.cpuUtilization, r.loadBalance));
                }
            }
        }
    }

    private static void parse(String[] args) {
        for (String arg : args) {
            if (arg.equals("-h") || arg.equals("--help")) {
                System.out.println("用法: AjlrLiteExperiment [--seeds=K|a,b,c] [--cases=N] "
                        + "[--tasks=N] [--cores=N] [--premium=R] [--out=DIR]"
                        + " [--policy=HEAD_ROOM|MIN_FINISH] [--alpha=R] [--premium=R]"
                        + " [--legacy-wfd-state]");
                System.exit(0);
            } else if (arg.startsWith("--seeds=")) {
                seeds = parseSeeds(arg.substring(8));
            } else if (arg.startsWith("--cases=")) {
                cases = Integer.parseInt(arg.substring(8));
            } else if (arg.startsWith("--tasks=")) {
                tasksPerCase = Integer.parseInt(arg.substring(8));
            } else if (arg.startsWith("--cores=")) {
                cores = Integer.parseInt(arg.substring(8));
            } else if (arg.startsWith("--policy=")) {
                policy = CacheAware_v3.Policy.valueOf(arg.substring(9).toUpperCase(Locale.ROOT));
            } else if (arg.startsWith("--alpha=")) {
                alpha = Double.parseDouble(arg.substring(8));
            } else if (arg.equals("--legacy-wfd-state")) {
                legacyWfdState = true;
            } else if (arg.startsWith("--premium=")) {
                premium = Double.parseDouble(arg.substring(10));
            } else if (arg.startsWith("--out=")) {
                outDir = arg.substring(6);
            } else {
                System.err.println("Unknown argument: " + arg);
                System.exit(2);
            }
        }
        if (cores <= 0 || cases <= 0 || tasksPerCase <= 0) {
            System.err.println("cores / cases / tasks 必须为正");
            System.exit(2);
        }
    }

    private static long[] parseSeeds(String spec) {
        String[] parts = spec.split(",", -1);
        if (parts.length == 1) {
            int n = Integer.parseInt(parts[0].trim());
            long[] out = new long[n];
            for (int i = 0; i < n; i++) {
                out[i] = i + 1L;
            }
            return out;
        }
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Long.parseLong(parts[i].trim());
        }
        return out;
    }
}