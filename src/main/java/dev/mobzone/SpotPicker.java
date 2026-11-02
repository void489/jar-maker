package dev.mobzone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Pure logic (no Bukkit) for finding spots a zombie can walk to: flood fill + spread-out picking. Unit-testable. */
public final class SpotPicker {
    public record Cell(int x, int y, int z) {}

    public interface Grid {
        /** Inside the zone box. */
        boolean inside(int x, int y, int z);
        /** Feet block and head block are free, and the block below is solid and not liquid. */
        boolean standable(int x, int y, int z);
        /** Block at x,y,z is free to move through. */
        boolean clear(int x, int y, int z);
    }

    private SpotPicker() {}

    /** Every cell reachable on foot from start (step up 1, drop up to 3). Capped so it can't run away. */
    public static Set<Cell> flood(Grid g, Cell start, int cap) {
        Set<Cell> seen = new HashSet<>();
        if (!g.inside(start.x, start.y, start.z) || !g.standable(start.x, start.y, start.z)) return seen;
        ArrayDeque<Cell> q = new ArrayDeque<>();
        seen.add(start);
        q.add(start);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty() && seen.size() < cap) {
            Cell c = q.poll();
            for (int[] d : dirs) {
                int nx = c.x + d[0], nz = c.z + d[1];
                for (int dy = 1; dy >= -3; dy--) {
                    int ny = c.y + dy;
                    if (!g.inside(nx, ny, nz)) continue;
                    if (dy == 1 && !g.clear(c.x, c.y + 2, c.z)) continue;
                    if (!g.standable(nx, ny, nz)) continue;
                    Cell n = new Cell(nx, ny, nz);
                    if (seen.add(n)) q.add(n);
                    break;
                }
            }
        }
        return seen;
    }

    /** How many reachable cells lie within radius r on the same floor. */
    public static int openness(Set<Cell> cells, Cell c, int r) {
        int n = 0;
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++)
                if (dx * dx + dz * dz <= r * r && cells.contains(new Cell(c.x + dx, c.y, c.z + dz))) n++;
        return n;
    }

    /** Cell with the most open space, ties broken by being closest to (cx, cz). */
    public static Cell bossSpot(Set<Cell> cells, double cx, double cz, int r) {
        Cell best = null; double bestScore = -1;
        for (Cell c : cells) {
            double score = openness(cells, c, r) * 1000.0 - Math.hypot(c.x - cx, c.z - cz);
            if (score > bestScore) { bestScore = score; best = c; }
        }
        return best;
    }

    /** Pick count cells, open areas first, as spread out as possible from each other and from the avoid list. */
    public static List<Cell> pick(Set<Cell> cells, int count, List<Cell> avoid, long seed, int minOpen, int[] spacings) {
        List<Cell> pool = new ArrayList<>();
        for (Cell c : cells) if (openness(cells, c, 4) >= minOpen) pool.add(c);
        if (pool.size() < count) { pool.clear(); pool.addAll(cells); }
        pool.sort((a, b) -> a.x != b.x ? Integer.compare(a.x, b.x) : a.z != b.z ? Integer.compare(a.z, b.z) : Integer.compare(a.y, b.y));
        Random rnd = new Random(seed);
        List<Cell> out = new ArrayList<>();
        List<Cell> all = new ArrayList<>(avoid);
        for (int i = 0; i < count; i++) {
            Cell chosen = null;
            for (int sp : spacings) {
                List<Cell> ok = new ArrayList<>();
                for (Cell c : pool) {
                    boolean good = true;
                    for (Cell o : all) if ((c.x - o.x) * (c.x - o.x) + (c.z - o.z) * (c.z - o.z) < sp * sp) { good = false; break; }
                    if (good) ok.add(c);
                }
                if (!ok.isEmpty()) { chosen = ok.get(rnd.nextInt(ok.size())); break; }
            }
            if (chosen == null) chosen = pool.get(rnd.nextInt(pool.size()));
            out.add(chosen); all.add(chosen);
        }
        return out;
    }
}
