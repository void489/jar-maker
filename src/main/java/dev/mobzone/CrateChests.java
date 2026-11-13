package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Crate keys + crate chests + the opening animation.
 * Keys live in plugins/MobZone/keys.yml, chest links in plugins/MobZone/chests.yml.
 */
public final class CrateChests implements Listener {
    /** Holder so we can recognise our own inventories. */
    private static final class Gui implements InventoryHolder {
        Inventory inv;
        boolean preview;
        // animation state
        Player player; CrateManager.Crate crate; CrateManager.Reward winner;
        List<CrateManager.Reward> strip; int step; boolean done;
        @Override public Inventory getInventory() { return inv; }
    }

    private static final int[] STEP_DELAY = new int[44];
    static {
        for (int i = 0; i < STEP_DELAY.length; i++) STEP_DELAY[i] = i < 22 ? 2 : i < 32 ? 3 : i < 38 ? 5 : i < 42 ? 8 : 12;
    }

    private final MobZonePlugin plugin;
    private final Map<String, String> chests = new HashMap<>();          // "world;x;y;z" -> crate id
    private final Map<UUID, Map<String, Integer>> keys = new HashMap<>();
    private final Map<UUID, Gui> opening = new HashMap<>();
    private File keyFile, chestFile;

    public CrateChests(MobZonePlugin plugin) { this.plugin = plugin; }

    // ---------------------------------------------------------------- storage

    public void reload() {
        keyFile = new File(plugin.getDataFolder(), "keys.yml");
        chestFile = new File(plugin.getDataFolder(), "chests.yml");
        chests.clear();
        keys.clear();
        var cy = YamlConfiguration.loadConfiguration(chestFile);
        var cs = cy.getConfigurationSection("chests");
        if (cs != null) for (String k : cs.getKeys(false)) chests.put(k.replace('|', ';'), cs.getString(k, ""));
        var ky = YamlConfiguration.loadConfiguration(keyFile);
        var ks = ky.getConfigurationSection("keys");
        if (ks != null) for (String u : ks.getKeys(false)) {
            try {
                var sec = ks.getConfigurationSection(u);
                if (sec == null) continue;
                Map<String, Integer> m = new HashMap<>();
                for (String c : sec.getKeys(false)) m.put(c, sec.getInt(c));
                keys.put(UUID.fromString(u), m);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    public void shutdown() {
        for (Gui g : new ArrayList<>(opening.values())) finish(g);
        opening.clear();
    }

    private void saveKeys() {
        var y = new YamlConfiguration();
        for (var e : keys.entrySet())
            for (var c : e.getValue().entrySet()) if (c.getValue() > 0) y.set("keys." + e.getKey() + "." + c.getKey(), c.getValue());
        try { y.save(keyFile); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save keys.yml: " + ex.getMessage()); }
    }

    private void saveChests() {
        var y = new YamlConfiguration();
        for (var e : chests.entrySet()) y.set("chests." + e.getKey().replace(';', '|'), e.getValue());
        try { y.save(chestFile); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save chests.yml: " + ex.getMessage()); }
    }

    private static String key(Location l) { return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ(); }

    // ---------------------------------------------------------------- keys API

    public int keysOf(UUID id, String crate) { return keys.getOrDefault(id, Map.of()).getOrDefault(crate, 0); }

    public void addKeys(UUID id, String crate, int n) {
        keys.computeIfAbsent(id, k -> new HashMap<>()).merge(crate, n, Integer::sum);
        if (keysOf(id, crate) < 0) keys.get(id).put(crate, 0);
        saveKeys();
    }

    private boolean takeKey(UUID id, String crate) {
        if (keysOf(id, crate) < 1) return false;
        keys.get(id).merge(crate, -1, Integer::sum);
        saveKeys();
        return true;
    }

    public void showKeys(CommandSender to, Player who) {
        StringBuilder sb = new StringBuilder("&#A3E635&lKeys &7for &f" + who.getName() + "&7: ");
        boolean any = false;
        for (String id : plugin.crates().ids()) {
            var c = plugin.crates().crate(id);
            sb.append(c.name()).append(" &f").append(keysOf(who.getUniqueId(), id)).append("  ");
            any = true;
        }
        Util.tell(to, any ? sb.toString() : "&cNo crates are set up.");
    }

    // ---------------------------------------------------------------- commands

    public void keyCommand(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("check") && a.length >= 3) {
            var t = Bukkit.getPlayerExact(a[2]);
            if (t == null) { Util.tell(s, "&cThat player isn't online."); return; }
            showKeys(s, t);
            return;
        }
        if ((sub.equals("give") || sub.equals("take")) && a.length >= 4) {
            var t = Bukkit.getPlayerExact(a[2]);
            String crate = a[3].toLowerCase(Locale.ROOT);
            if (t == null) { Util.tell(s, "&cThat player isn't online."); return; }
            if (plugin.crates().crate(crate) == null) { Util.tell(s, "&cNo such crate. Crates: " + String.join(", ", plugin.crates().ids())); return; }
            int n;
            try { n = a.length > 4 ? Integer.parseInt(a[4]) : 1; } catch (NumberFormatException e) { Util.tell(s, "&cAmount must be a number."); return; }
            if (n < 1) { Util.tell(s, "&cAmount must be 1 or more."); return; }
            if (sub.equals("take")) n = -Math.min(n, keysOf(t.getUniqueId(), crate));
            addKeys(t.getUniqueId(), crate, n);
            String name = plugin.crates().crate(crate).name();
            if (n > 0) Util.tell(t, "&#A3E635[Crate] &7You received &f" + n + "x &7" + name + " &7key" + (n > 1 ? "s" : "") + "!");
            Util.tell(s, "&aDone. " + t.getName() + " now has " + keysOf(t.getUniqueId(), crate) + " " + crate + " key(s).");
            return;
        }
        if (sub.equals("buy") && a.length >= 4) {
            var t = Bukkit.getPlayerExact(a[2]);
            if (t == null) { Util.tell(s, "&cThat player isn't online."); return; }
            int n;
            try { n = a.length > 4 ? Integer.parseInt(a[4]) : 1; } catch (NumberFormatException e) { Util.tell(s, "&cAmount must be a number."); return; }
            buy(t, a[3], n);
            return;
        }
        Util.tell(s, "&e/mobzone key give <player> <crate> [amount]   /mobzone key take <player> <crate> [amount]   /mobzone key buy <player> <crate> [amount]   /mobzone key check <player>");
    }

    /** Spends Bio Tokens (the crate's "cost" in config.yml) on keys. Used by the /crates key shop. */
    public void buy(Player p, String crateId, int amount) {
        var c = plugin.crates().crate(crateId);
        if (c == null || amount < 1 || amount > 64) { Util.tell(p, "&cCouldn't buy that."); return; }
        long total = c.cost() * amount;
        String raw = RankBonus.placeholder(p, "%playerpoints_points%");
        long bal;
        try { bal = Long.parseLong(raw == null ? "" : raw.replace(",", "").trim()); }
        catch (NumberFormatException e) { Util.tell(p, "&cCouldn't read your Bio Tokens. Tell an admin."); return; }
        if (bal < total) {
            Util.tell(p, "&cYou need &b" + total + " Bio Tokens &cfor that. You have &b" + bal + "&c.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        Util.console("points take " + p.getName() + " " + total);
        addKeys(p.getUniqueId(), c.id(), amount);
        Util.tell(p, "&#A3E635[Keys] &7Bought &f" + amount + "x &7" + c.name() + " &7key" + (amount > 1 ? "s" : "")
                + " for &b" + total + " Bio Tokens&7. You have &f" + keysOf(p.getUniqueId(), c.id()) + "&7. Open them in the lobby (&f/warp crates&7).");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    public void chestCommand(CommandSender s, String[] a) {
        String sub = a[1].toLowerCase(Locale.ROOT);
        if (sub.equals("chests")) {
            if (chests.isEmpty()) { Util.tell(s, "&eNo crate chests linked yet."); return; }
            for (var e : chests.entrySet()) Util.tell(s, "&7" + e.getValue() + " &f@ " + e.getKey().replace(';', ' '));
            return;
        }
        if (!(s instanceof Player p)) { Util.tell(s, "&cPlayers only."); return; }
        Block b = p.getTargetBlockExact(6);
        if (b == null || b.getType() == Material.AIR) { Util.tell(s, "&cLook at the chest first (within 6 blocks)."); return; }
        if (sub.equals("unlink")) {
            if (chests.remove(key(b.getLocation())) != null) { saveChests(); Util.tell(s, "&aChest unlinked."); }
            else Util.tell(s, "&cThat block isn't a crate chest.");
            return;
        }
        if (a.length < 3 || plugin.crates().crate(a[2]) == null) {
            Util.tell(s, "&e/mobzone crate link <crate>  (crates: " + String.join(", ", plugin.crates().ids()) + ")");
            return;
        }
        chests.put(key(b.getLocation()), a[2].toLowerCase(Locale.ROOT));
        saveChests();
        Util.tell(s, "&aLinked this " + b.getType().name().toLowerCase(Locale.ROOT) + " to the " + a[2].toLowerCase(Locale.ROOT) + " crate.");
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_BLOCK && act != Action.LEFT_CLICK_BLOCK) return;
        String crateId = chests.get(key(e.getClickedBlock().getLocation()));
        if (crateId == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        var crate = plugin.crates().crate(crateId);
        if (crate == null) { Util.tell(p, "&cThis crate isn't set up. Tell an admin."); return; }
        if (act == Action.LEFT_CLICK_BLOCK) { preview(p, crate); return; }
        if (opening.containsKey(p.getUniqueId())) return;
        if (!takeKey(p.getUniqueId(), crate.id())) {
            Util.tell(p, "&cYou need a " + crate.name() + " &ckey. &7Left-click the chest to see the rewards.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            p.setVelocity(p.getLocation().getDirection().multiply(-0.4).setY(0.25));
            return;
        }
        animate(p, crate);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (chests.containsKey(key(e.getBlock().getLocation()))) {
            e.setCancelled(true);
            Util.tell(e.getPlayer(), "&cUse /mobzone crate unlink first to remove this crate chest.");
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Gui g && !g.preview && !g.done) finish(g);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Gui g = opening.get(e.getPlayer().getUniqueId());
        if (g != null) finish(g);
    }

    // ---------------------------------------------------------------- preview

    private static ItemStack icon(CrateManager.Reward r) {
        Material m = switch (r.type()) {
            case "cash" -> Material.EMERALD;
            case "tokens" -> Material.AMETHYST_SHARD;
            case "booster" -> Material.BLAZE_POWDER;
            default -> Material.NETHER_STAR;
        };
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        String text = r.text().isEmpty() ? CrateManager.describe(r) : r.text();
        meta.displayName(Util.c("&f" + text).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack named(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Util.c(name).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    private void preview(Player p, CrateManager.Crate c) {
        Gui g = new Gui();
        g.preview = true;
        int rows = Math.min(6, Math.max(1, (c.rewards().size() + 8) / 9));
        g.inv = Bukkit.createInventory(g, rows * 9, Util.c(c.name() + " &8- rewards"));
        int total = 0;
        for (var r : c.rewards()) total += Math.max(1, r.weight());
        int i = 0;
        for (var r : c.rewards()) {
            if (i >= rows * 9) break;
            ItemStack it = icon(r);
            ItemMeta meta = it.getItemMeta();
            double pct = 100.0 * Math.max(1, r.weight()) / total;
            meta.lore(List.of(Util.c("&7Chance: &a" + String.format(Locale.US, "%.1f", pct) + "%")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
            it.setItemMeta(meta);
            g.inv.setItem(i++, it);
        }
        p.openInventory(g.inv);
    }

    // ---------------------------------------------------------------- animation

    private void animate(Player p, CrateManager.Crate c) {
        var rnd = ThreadLocalRandom.current();
        Gui g = new Gui();
        g.player = p;
        g.crate = c;
        g.winner = c.rewards().get(CrateMath.pick(c.weights(), rnd.nextDouble()));
        int n = STEP_DELAY.length;
        g.strip = new ArrayList<>();
        for (int i = 0; i < n + 9; i++) g.strip.add(c.rewards().get(CrateMath.pick(c.weights(), rnd.nextDouble())));
        g.strip.set(n + 4, g.winner);   // centre slot shows strip[step + 4]
        g.inv = Bukkit.createInventory(g, 27, Util.c(c.name()));
        ItemStack glass = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) g.inv.setItem(i, glass);
        g.inv.setItem(4, named(Material.LIME_STAINED_GLASS_PANE, "&a▼"));
        g.inv.setItem(22, named(Material.LIME_STAINED_GLASS_PANE, "&a▲"));
        opening.put(p.getUniqueId(), g);
        p.openInventory(g.inv);
        draw(g);
        tick(g);
    }

    private void draw(Gui g) {
        for (int j = 0; j < 9; j++) g.inv.setItem(9 + j, icon(g.strip.get(g.step + j)));
    }

    private void tick(Gui g) {
        if (g.done) return;
        if (g.step >= STEP_DELAY.length) { finish(g); return; }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (g.done) return;
            g.step++;
            draw(g);
            g.player.playSound(g.player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.0f + g.step * 0.02f);
            tick(g);
        }, g.step < STEP_DELAY.length ? STEP_DELAY[g.step] : 1L);
    }

    /** Pays the prize exactly once, whether the animation ended, the player closed the GUI, or they quit. */
    private void finish(Gui g) {
        if (g.done) return;
        g.done = true;
        opening.remove(g.player.getUniqueId());
        Player p = g.player;
        var r = g.winner;
        String text = r.text().isEmpty() ? CrateManager.describe(r) : r.text();
        plugin.crates().give(p, r);
        if (p.isOnline()) {
            Util.tell(p, "&#A3E635[Crate] &7You opened " + g.crate.name() + "&7 and won &f" + text + "&7!");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1.2, 0), 40, 0.5, 0.6, 0.5, 0.2);
            int left = keysOf(p.getUniqueId(), g.crate.id());
            if (left > 0) Util.tell(p, "&7You have &f" + left + " &7key" + (left > 1 ? "s" : "") + " left.");
            // show the winner for a moment, then close
            if (plugin.isEnabled() && p.getOpenInventory().getTopInventory().getHolder() == g) {
                g.inv.setItem(13, icon(r));
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() == g) p.closeInventory();
                }, 30L);
            }
        }
        if (r.broadcast())
            Util.broadcast("&#A3E635[Crate] &f" + p.getName() + " &7won &f" + text + " &7from " + g.crate.name() + "&7!");
    }
}
