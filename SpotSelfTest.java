import dev.mobzone.SpotPicker;
import dev.mobzone.SpotPicker.*;
import java.util.*;
public class SpotSelfTest {
    public static void main(String[] a) {
        // 60x40 floor with a wall splitting it except a door, and an unreachable island
        Grid g = new Grid() {
            boolean wall(int x, int z) { return x == 30 && z != 20; }
            boolean island(int x, int z) { return x >= 50 && x <= 55 && z >= 30; }
            public boolean inside(int x, int y, int z) { return x >= 0 && x < 60 && z >= 0 && z < 40 && y >= 5 && y <= 8; }
            public boolean standable(int x, int y, int z) { return y == 5 && !wall(x, z) && !island(x, z) ; }
            public boolean clear(int x, int y, int z) { return true; }
        };
        Set<Cell> r = SpotPicker.flood(g, new Cell(5, 5, 5), 100000);
        System.out.println("reach " + r.size());
        if (r.contains(new Cell(52, 5, 35))) throw new AssertionError("island reached");
        if (!r.contains(new Cell(45, 5, 5))) throw new AssertionError("door not crossed");
        Cell boss = SpotPicker.bossSpot(r, 30, 20, 4);
        List<Cell> sp = SpotPicker.pick(r, 4, List.of(boss), 7, 30, new int[]{16, 10, 6, 0});
        System.out.println("boss " + boss + " spawns " + sp);
        for (Cell c : sp) if (!r.contains(c)) throw new AssertionError("unreachable pick");
        System.out.println("OK");
    }
}
