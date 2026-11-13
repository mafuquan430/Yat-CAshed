package analyzer;

import org.apache.commons.math3.distribution.TDistribution;

/** Student-t mean interval; callers must supply independent finite observations. */
public final class ConfidenceInterval {
    private ConfidenceInterval() {}

    public static double critical95(int n) {
        if (n < 2) throw new IllegalArgumentException("At least two observations required");
        return new TDistribution(n - 1.0).inverseCumulativeProbability(0.975);
    }

    /** Returns NaN for a singleton: the sampling variance cannot be estimated. */
    public static double halfWidth95(double[] values) {
        if (values == null || values.length == 0)
            throw new IllegalArgumentException("Observations must not be empty");
        double mean = 0, m2 = 0;
        int n = 0;
        for (double x : values) {
            if (!Double.isFinite(x)) throw new IllegalArgumentException("Non-finite observation");
            double delta = x - mean;
            mean += delta / ++n;
            m2 += delta * (x - mean);
        }
        if (n == 1) return Double.NaN;
        double width = critical95(n) * Math.sqrt(m2 / (n - 1) / n);
        if (!Double.isFinite(width)) throw new IllegalArgumentException("Observation range overflow");
        return width;
    }
}
