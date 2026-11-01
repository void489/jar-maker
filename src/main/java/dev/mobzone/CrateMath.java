package dev.mobzone;

/** Pure weighted-pick logic, kept separate so it can be tested without a server. */
final class CrateMath {
    private CrateMath() {}

    /** r01 in [0,1). Returns the index chosen. Zero or negative weights are never picked. */
    static int pick(int[] weights, double r01) {
        long total = 0;
        for (int w : weights) if (w > 0) total += w;
        if (total <= 0) return 0;
        double t = r01 * total;
        int last = 0;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] <= 0) continue;
            last = i;
            t -= weights[i];
            if (t < 0) return i;
        }
        return last;
    }
}
