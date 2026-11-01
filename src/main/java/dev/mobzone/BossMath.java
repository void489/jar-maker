package dev.mobzone;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Pure maths for splitting a boss reward by damage dealt (no Bukkit types, unit tested). */
public final class BossMath {
    private BossMath() {}

    /**
     * Splits {@code total} between players in proportion to damage. Players below {@code minDamage}
     * get nothing. Result is ordered by damage, highest first, and always sums to exactly {@code total}
     * (rounding leftovers go to the top damager). Empty if nobody qualifies.
     */
    public static Map<UUID, Long> split(Map<UUID, Double> damage, double minDamage, long total) {
        Map<UUID, Double> eligible = new LinkedHashMap<>();
        damage.entrySet().stream()
                .filter(e -> e.getValue() > 0 && e.getValue() >= minDamage)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> eligible.put(e.getKey(), e.getValue()));
        Map<UUID, Long> out = new LinkedHashMap<>();
        if (eligible.isEmpty() || total <= 0) return out;

        double sum = 0;
        for (double v : eligible.values()) sum += v;
        long given = 0;
        UUID top = null;
        for (Map.Entry<UUID, Double> e : eligible.entrySet()) {
            if (top == null) top = e.getKey();
            long amount = (long) Math.floor(total * e.getValue() / sum);
            out.put(e.getKey(), amount);
            given += amount;
        }
        out.merge(top, total - given, Long::sum);
        return out;
    }
}
