package dev.mobzone;

import java.util.LinkedHashSet;
import java.util.Set;

/** A cuboid zone. Pure geometry (no Bukkit types) so the maths can be tested on its own. */
public final class Zone {
    public final String name;
    public final String world;
    public final int minX, minY, minZ, maxX, maxY, maxZ;
    /** Spawner names, stored lowercase. */
    public final Set<String> spawners = new LinkedHashSet<>();

    public Zone(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.name = name;
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.maxX = Math.max(x1, x2);
        this.minY = Math.min(y1, y2);
        this.maxY = Math.max(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxZ = Math.max(z1, z2);
    }

    // Block coordinates are inclusive, so the zone covers [min, max + 1) on each axis.
    // The margin is capped so a very thin zone never gets an empty inside.
    public double loX(double m) { return minX + Math.min(m, (maxX + 1 - minX) / 2.0); }
    public double hiX(double m) { return maxX + 1 - Math.min(m, (maxX + 1 - minX) / 2.0); }
    public double loZ(double m) { return minZ + Math.min(m, (maxZ + 1 - minZ) / 2.0); }
    public double hiZ(double m) { return maxZ + 1 - Math.min(m, (maxZ + 1 - minZ) / 2.0); }

    public boolean contains(double x, double y, double z, double m) {
        return x >= loX(m) && x <= hiX(m)
                && z >= loZ(m) && z <= hiZ(m)
                && y >= minY && y <= maxY + 1;
    }

    public double clampX(double x, double m) { return Math.max(loX(m), Math.min(hiX(m), x)); }
    public double clampZ(double z, double m) { return Math.max(loZ(m), Math.min(hiZ(m), z)); }
    public double clampY(double y) { return Math.max(minY, Math.min(maxY + 1, y)); }

    /**
     * Factor (0..1) to apply to one axis of a knockback vector. Full strength when far from the wall
     * it points at, fading to zero at the wall.
     */
    public static double guardScale(double pos, double k, double lo, double hi, double guard) {
        if (k > 0) {
            double d = hi - pos;
            return d >= guard ? 1.0 : Math.max(0.0, d / guard);
        }
        if (k < 0) {
            double d = pos - lo;
            return d >= guard ? 1.0 : Math.max(0.0, d / guard);
        }
        return 1.0;
    }

    public boolean nearEdge(double x, double z, double m, double band) {
        return x < loX(m) + band || x > hiX(m) - band || z < loZ(m) + band || z > hiZ(m) - band;
    }
}
