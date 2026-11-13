package allocation;

import java.util.List;

import entity.Node;

/**
 * AJLR-lite：把「历史表 + CRP 预测」真正接进分配决策（CacheAware_v3）。
 *
 * <h3>为什么需要它</h3>
 * {@link CacheAware_v2} 的五维加权评分只用到了任务的静态属性
 * （{@code sensitivity}、{@code weights}、随机 {@code affinity}、以及它自己
 * 维护的一个"缓存利用率"近似值），<b>完全没有</b>用到"这个任务（或这个 DAG）
 * 上次在哪个核上跑过"这一信息。而 {@code entity.SharedCacheModel} 的命中判定
 * 恰恰只看这一件事：最近 5 次访问里是否存在同一个 {@code dagID}。
 * 于是 v2 在结构上无法利用模型提供的唯一局部性信号——这不是"权重没调好"，
 * 而是决策变量缺了一项。
 *
 * <p>本类按论文（以及内核 {@code select_task_rq_yat_casched()}）的做法实现：
 * <ol>
 *   <li>为每个核维护"最近一次执行的 DAG 及访问序号"（L1 范围）；</li>
 *   <li>为每个 L2 共享组维护同样的一行（L2 范围）；</li>
 *   <li>维护全局的一行（L3 范围）；</li>
 *   <li>用 CRP 比率把"预测命中级别"折算成预期执行时间；</li>
 *   <li>选使 <b>预测完成时间</b> {@code load[cpu] + predEt} 最小的核，
 *       负载只作为预测的一部分参与比较，不再单独加权。</li>
 * </ol>
 *
 * <p>与 v2 的差别只在"评分函数"，不改任务、不改模型、不改统计口径，
 * 三者用同一个 {@code SharedCacheModel} 计算实际执行时间与命中率。
 *
 * <p><b>预测器的诚实边界</b>：默认的 {@link #DEFAULT_FACTORS} 与
 * {@code SharedCacheModel} 的系数相同。这相当于"CRP 已经被学准"的理想情形，
 * 给出的是<b>该决策规则的上界</b>。真实系统里 CRP 由测量得到、存在误差，
 * 因此还提供 {@link #setNoise(double)} 做误差敏感性实验
 * （对系数做乘性扰动），不要把默认配置当成已标定的 CRP。
 *
 * <p>非线程安全；每个用例新建一个实例。
 */
public class CacheAware_v3 extends AllocationMethods {

    /** 与 {@code SharedCacheModel.RECENT_WINDOW} 一致的回看窗口。 */
    public static final int RECENT_WINDOW = 5;

    /** L1 / L2 / L3 / 内存 的执行时间比率（预测用）。 */
    public static final double[] DEFAULT_FACTORS = {0.80, 0.90, 0.95, 1.20};

    /**
     * 选择规则。
     *
     * <ul>
     *   <li>{@link #MIN_FINISH}：直接最小化预测完成时间 {@code load + predEt}。
     *       这是"把负载与预测收益压进同一个标量"的做法；实测它会过度分散——
     *       同一 DAG 的第二个任务看到首个任务把本核负载抬高了一个 et，
     *       于是被送回空核，L1 命中永远拿不到。</li>
     *   <li>{@link #HEAD_ROOM}：只在"负载不超过最轻核 {@code alpha*base}"的核里做
     *       MSF（优先 L1 &gt; L2 &gt; L3 &gt; miss）。等价于 AJLR 在
     *       "候选核集合"上做最大加速比选择，负载窗口就是候选集的定义。</li>
     * </ul>
     */
    public enum Policy { MIN_FINISH, HEAD_ROOM }

    private static Policy policy = Policy.HEAD_ROOM;

    /** HEAD_ROOM 的负载窗口宽度（单位：任务基准执行时间）。 */
    private static double headroomAlpha = 1.0;

    public static void setPolicy(Policy p, double alpha) {
        if (p == null) {
            throw new IllegalArgumentException("policy 不能为 null");
        }
        if (!(alpha >= 0.0) || Double.isNaN(alpha)) {
            throw new IllegalArgumentException("alpha 必须是非负数");
        }
        policy = p;
        headroomAlpha = alpha;
    }

    public static Policy getPolicy() {
        return policy;
    }

    public static double getHeadroomAlpha() {
        return headroomAlpha;
    }

    private static double[] assumedFactors = DEFAULT_FACTORS.clone();
    private static double premiumScale = 1.0;

    /**
     * 设置预测系数（长度必须为 4）。
     */
    public static void setAssumedFactors(double[] factors) {
        if (factors == null || factors.length != 4) {
            throw new IllegalArgumentException("factors 必须是长度 4 的数组");
        }
        double[] copy = new double[4];
        for (int i = 0; i < 4; i++) {
            if (!(factors[i] > 0.0) || Double.isNaN(factors[i])) {
                throw new IllegalArgumentException("factors[" + i + "] 必须为正有限数");
            }
            copy[i] = factors[i];
        }
        assumedFactors = copy;
    }

    public static double[] getAssumedFactors() {
        return assumedFactors.clone();
    }

    /**
     * 预测误差敏感性：缩放"局部性溢价"，而不是给各级系数各自加噪声。
     *
     * <p>理由：若对四个系数独立扰动，很容易把 L2 算得比 L1 还便宜
     * （即连"排序"都错了），那测的就不是 CRP 精度而是预测器彻底失效。
     * 这里改用一个可解释的单参数模型：设真实系数为 {@code f_k}，
     * 预测系数取 {@code 1 - s*(1-f_k)}（内存级为 {@code 1 + s*(1.2-1)}），
     * 于是
     * <ul>
     *   <li>{@code s = 1}：CRP 与模型完全一致（默认）；</li>
     *   <li>{@code s < 1}：系统性低估局部性收益；</li>
     *   <li>{@code s > 1}：系统性高估局部性收益（"过度自信的 CRP"）。</li>
     * </ul>
     * 任何 {@code s > 0} 都保持 L1 &lt; L2 &lt; L3 &lt; 内存 的排序，
     * 因此这个实验回答的是"溢价估得准不准"，不是"排序对不对"。
     */
    public static void setPremiumScale(double s) {
        if (!(s > 0.0) || Double.isNaN(s)) {
            throw new IllegalArgumentException("premium scale 必须为正有限数: " + s);
        }
        premiumScale = s;
        assumedFactors = new double[] {
            1.0 - s * (1.0 - DEFAULT_FACTORS[0]),
            1.0 - s * (1.0 - DEFAULT_FACTORS[1]),
            1.0 - s * (1.0 - DEFAULT_FACTORS[2]),
            1.0 + s * (DEFAULT_FACTORS[3] - 1.0)
        };
    }

    public static double getPremiumScale() {
        return premiumScale;
    }

    private final int cores;
    private final int l2Group;

    /** 每个核的预测累计完成时间（ns）。 */
    private final long[] load;

    private final int[] lastDagL1;
    private final int[] lastIdxL1;
    private final int[] countL1;

    private final int[] lastDagL2;
    private final int[] lastIdxL2;
    private final int[] countL2;

    private int lastDagL3 = -1;
    private int lastIdxL3 = 0;
    private int countL3 = 0;

    /**
     * @param cores   处理器数量
     * @param l2Group 每多少个核共享一个 L2（&gt;= 1）
     */
    public CacheAware_v3(int cores, int l2Group) {
        if (cores <= 0) {
            throw new IllegalArgumentException("cores 必须为正数: " + cores);
        }
        this.cores = cores;
        this.l2Group = Math.max(1, l2Group);
        this.load = new long[cores];
        this.lastDagL1 = new int[cores];
        this.lastIdxL1 = new int[cores];
        this.countL1 = new int[cores];
        this.lastDagL2 = new int[cores];
        this.lastIdxL2 = new int[cores];
        this.countL2 = new int[cores];
        for (int i = 0; i < cores; i++) {
            lastDagL1[i] = -1;
            lastDagL2[i] = -1;
        }
    }

    /** 本核 / 本 L2 组 / 全局三级历史中，任务应当命中的缓存级别（1..4）。 */
    public int predictLevel(int cpu, int dagId) {
        if (lastDagL1[cpu] == dagId && countL1[cpu] - lastIdxL1[cpu] <= RECENT_WINDOW) {
            return 1;
        }
        int group = cpu / l2Group;
        if (lastDagL2[group] == dagId && countL2[group] - lastIdxL2[group] <= RECENT_WINDOW) {
            return 2;
        }
        if (lastDagL3 == dagId && countL3 - lastIdxL3 <= RECENT_WINDOW) {
            return 3;
        }
        return 4;
    }

    @Override
    public int allocate(List<Node> readyNodes, List<Integer> availableProc) {
        if (readyNodes == null || readyNodes.isEmpty()
                || availableProc == null || availableProc.isEmpty()) {
            return -1;
        }

        Node task = readyNodes.get(0);
        long base = Math.max(0L, task.expectedET);
        int dagId = task.getDagID();

        int bestCpu = -1;
        long bestFinish = Long.MAX_VALUE;
        long bestLoad = Long.MAX_VALUE;
        long bestEt = 0;
        int bestLevel = Integer.MAX_VALUE;

        if (policy == Policy.MIN_FINISH) {
            for (int cpu : availableProc) {
                if (cpu < 0 || cpu >= cores) {
                    continue;
                }
                int level = predictLevel(cpu, dagId);
                long predictedEt = predictedEt(base, level);
                long finish = load[cpu] + predictedEt;
                boolean better;
                if (finish != bestFinish) {
                    better = finish < bestFinish;
                } else if (load[cpu] != bestLoad) {
                    better = load[cpu] < bestLoad;
                } else {
                    better = cpu < bestCpu;
                }
                if (better) {
                    bestCpu = cpu;
                    bestFinish = finish;
                    bestLoad = load[cpu];
                    bestEt = predictedEt;
                }
            }
            if (bestCpu >= 0) {
                commit(bestCpu, dagId, bestEt);
            }
            return bestCpu;
        }

        /* HEAD_ROOM：先取最轻负载，再在该窗口内按 MSF 选核。 */
        long minLoad = Long.MAX_VALUE;
        for (int cpu : availableProc) {
            if (cpu >= 0 && cpu < cores && load[cpu] < minLoad) {
                minLoad = load[cpu];
            }
        }
        long limit = minLoad + (long) (headroomAlpha * base);
        for (int cpu : availableProc) {
            if (cpu < 0 || cpu >= cores) {
                continue;
            }
            if (load[cpu] > limit) {
                continue;
            }
            int level = predictLevel(cpu, dagId);
            long predictedEt = predictedEt(base, level);
            boolean better;
            if (level != bestLevel) {
                better = level < bestLevel;
            } else if (load[cpu] != bestLoad) {
                better = load[cpu] < bestLoad;
            } else {
                better = cpu < bestCpu;
            }
            if (better) {
                bestCpu = cpu;
                bestLevel = level;
                bestLoad = load[cpu];
                bestEt = predictedEt;
                bestFinish = load[cpu] + predictedEt;
            }
        }
        if (bestCpu >= 0) {
            commit(bestCpu, dagId, bestEt);
        }
        return bestCpu;
    }

    private static long predictedEt(long base, int level) {
        long et = (long) (base * assumedFactors[level - 1]);
        return et > 0 ? et : 1L;
    }

    private void commit(int cpu, int dagId, long et) {
        load[cpu] += et;

        lastDagL1[cpu] = dagId;
        lastIdxL1[cpu] = countL1[cpu]++;

        int group = cpu / l2Group;
        lastDagL2[group] = dagId;
        lastIdxL2[group] = countL2[group]++;

        lastDagL3 = dagId;
        lastIdxL3 = countL3++;
    }

    /** 复位全部历史与负载（复用一个实例跑多个用例时使用）。 */
    public void reset() {
        for (int i = 0; i < cores; i++) {
            load[i] = 0L;
            lastDagL1[i] = -1;
            lastIdxL1[i] = 0;
            countL1[i] = 0;
            lastDagL2[i] = -1;
            lastIdxL2[i] = 0;
            countL2[i] = 0;
        }
        lastDagL3 = -1;
        lastIdxL3 = 0;
        countL3 = 0;
    }

    /** 每核预测负载快照（调试与单测用）。 */
    public long[] loadSnapshot() {
        return load.clone();
    }
}