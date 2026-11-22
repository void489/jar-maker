package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /mobzone resetplayer <name> [confirm]   and   /mobzone reset server [confirm]
 * Wipes progress: kills, killgates, rank, prestige, upgrades, tokens, cash, keys, unclaimed cash, quests, gear.
 * Keeps staff and donor groups. Commands run in small batches so a big server does not lag.
 * Things that need the player online and in prison (clear inventory, attribute bonuses, fresh gear) are queued
 * and applied the next time they stand in the prison world (plugins/MobZone/resets.yml).
 */
public final class ResetManager implements Listener {
    private static final String[] ZONES = {"cellblock", "cafeteria", "laundry", "infirmary", "yard", "sewers", "lab", "quarantine", "subbasement"};
    private static final String[] ENCH = {"sharpness", "double_strike", "vampire", "temper", "venom", "ice_aspect", "thunder", "rage"};
    private static final String[] ARM = {"prot", "blast", "thorns", "hardened", "fire_shield", "dragon_heart", "speed"};

    private final MobZonePlugin plugin;
    private final Set<UUID> pendingGear = new HashSet<>();
    private final Set<UUID> pendingOb = new HashSet<>();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Map<String, Long> confirms = new HashMap<>();
    private File file;
    private int taskId = -1;

    public ResetManager(MobZonePlugin plugin) { this.plugin = plugin; }

    // ---------------------------------------------------------------- storage

    public void reload() {
        file = new File(plugin.getDataFolder(), "resets.yml");
        pendingGear.clear();
        pendingOb.clear();
        var y = YamlConfiguration.loadConfiguration(file);
        for (String u : y.getStringList("pending")) {
            try { pendingGear.add(UUID.fromString(u)); } catch (IllegalArgumentException ignored) { }
        }
        for (String u : y.getStringList("pending-oneblock")) {
            try { pendingOb.add(UUID.fromString(u)); } catch (IllegalArgumentException ignored) { }
        }
    }

    private void save() {
        var y = new YamlConfiguration();
        List<String> l = new ArrayList<>();
        for (UUID u : pendingGear) l.add(u.toString());
        y.set("pending", l);
        List<String> ol = new ArrayList<>();
        for (UUID u : pendingOb) ol.add(u.toString());
        y.set("pending-oneblock", ol);
        try { y.save(file); } catch (IOException e) { plugin.getLogger().warning("Couldn't save resets.yml: " + e.getMessage()); }
    }

    // ---------------------------------------------------------------- command queue

    private void enqueue(List<String> cmds) {
        queue.addAll(cmds);
        if (taskId != -1) return;
        int perTick = Math.max(1, plugin.getConfig().getInt("reset.commands-per-tick", 8));
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            for (int i = 0; i < perTick && !queue.isEmpty(); i++) Util.console(queue.poll());
            if (queue.isEmpty()) { Bukkit.getScheduler().cancelTask(taskId); taskId = -1; }
        }, 1L, 1L);
    }

    public void shutdown() {
        // flush what is left so a restart does not leave a half-done reset
        while (!queue.isEmpty()) Util.console(queue.poll());
        if (taskId != -1) { Bukkit.getScheduler().cancelTask(taskId); taskId = -1; }
    }

    /** The console commands that wipe one player's progress (works for offline players too). */
    private List<String> commandsFor(String n, UUID id, boolean eco) {
        List<String> c = new ArrayList<>();
        c.add("minecraft:scoreboard players reset " + n + " kills");
        for (String z : ZONES) c.add("lp user " + id + " parent remove killgate_" + z);
        for (int i = 1; i <= 10; i++) c.add("lp user " + id + " parent remove prestige_" + i);
        for (char ch = 'b'; ch <= 'z'; ch++) c.add("lp user " + id + " parent remove rank_" + ch);
        c.add("lp user " + id + " parent add rank_a");
        c.add("lp user " + id + " meta unset prestige");
        c.add("lp user " + id + " meta unset prestige_title");
        for (String e : ENCH) c.add("lp user " + id + " meta unset ench_" + e);
        for (String a : ARM) c.add("lp user " + id + " meta unset arm_" + a);
        c.add("points reset " + n);
        if (eco) c.add("eco reset " + n);
        String rc = plugin.getConfig().getString("reset.rank-command", "ranks set rank {player} A default");
        if (!rc.isBlank()) c.add(rc.replace("{player}", n));
        return c;
    }

    // ---------------------------------------------------------------- commands

    private boolean confirmed(CommandSender s, String key, String confirmWord, String warning) {
        long now = System.currentTimeMillis();
        String who = s.getName() + ":" + key;
        if (confirmWord != null && confirmWord.equalsIgnoreCase("confirm") && now - confirms.getOrDefault(who, 0L) < 30_000L) {
            confirms.remove(who);
            return true;
        }
        confirms.put(who, now);
        Util.tell(s, warning);
        return false;
    }

    private static final List<String> SCOPES = List.of("prison", "oneblock", "smp", "all");

    static boolean isScope(String x) { return x != null && SCOPES.contains(x.toLowerCase(Locale.ROOT)); }

    private static boolean has(String scope, String mode) { return scope.equals("all") || scope.equals(mode); }

    private static String describe(String scope) {
        return switch (scope) {
            case "prison" -> "PRISON progress (kills, gates, rank, prestige, upgrades, tokens, cash, keys, quests, gear)";
            case "oneblock" -> "ONEBLOCK progress (island, island level, Oneblock cash, inventory)";
            case "smp" -> "SMP progress";
            default -> "EVERYTHING: Prison, Oneblock and SMP progress";
        };
    }

    private List<String> listOf(String path, String n, UUID id) {
        List<String> out = new ArrayList<>();
        for (String c : plugin.getConfig().getStringList(path))
            if (!c.isBlank()) out.add(c.replace("{player}", n).replace("{uuid}", id.toString()));
        return out;
    }

    private String startCash(String mode) { return String.valueOf(plugin.getConfig().getLong("wallets.start." + mode, 0L)); }

    /** Console commands that wipe one player for the given scope. */
    private List<String> planFor(String n, UUID id, String scope) {
        List<String> c = new ArrayList<>();
        boolean all = scope.equals("all");
        if (has(scope, "prison")) c.addAll(commandsFor(n, id, all));
        if (has(scope, "oneblock")) c.addAll(listOf("reset.oneblock-commands", n, id));
        if (has(scope, "smp")) c.addAll(listOf("reset.smp-commands", n, id));
        if (all) plugin.wallets().resetPlayer(id);
        else {
            String cmd = plugin.wallets().resetMode(id, scope, n);
            if (cmd != null) c.add(cmd);
        }
        return c;
    }

    private void applyPluginState(UUID id, String scope) {
        if (has(scope, "prison")) {
            plugin.chests().resetPlayer(id);
            plugin.quests().resetPlayer(id);
            pendingGear.add(id);
        }
        if (has(scope, "oneblock")) pendingOb.add(id);
    }

    public void resetPlayer(CommandSender s, String name, String scopeWord, String confirmWord) {
        String scope = scopeWord == null ? "all" : scopeWord.toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) { Util.tell(s, "&e/mobzone resetplayer <player> [prison|oneblock|smp|all] [confirm]"); return; }
        OfflinePlayer t = Bukkit.getPlayerExact(name);
        if (t == null) {
            t = Bukkit.getOfflinePlayerIfCached(name);
            if (t == null || !t.hasPlayedBefore()) { Util.tell(s, "&cI don't know a player called " + name + "."); return; }
        }
        String n = t.getName() == null ? name : t.getName();
        if (!confirmed(s, "p:" + n.toLowerCase(Locale.ROOT) + ":" + scope, confirmWord,
                "&e&lThis wipes " + describe(scope) + " for " + n + ". Staff and donor ranks stay.\n"
                        + "&eType &f/mobzone resetplayer " + n + " " + scope + " confirm &ewithin 30 seconds to do it.")) return;
        enqueue(planFor(n, t.getUniqueId(), scope));
        applyPluginState(t.getUniqueId(), scope);
        save();
        Player on = t.getPlayer();
        if (on != null && has(scope, "oneblock")) Bukkit.getScheduler().runTaskLater(plugin, () -> applyOb(on), 60L);
        if (on != null && has(scope, "prison")) {
            Util.tell(on, "&#A3E635[Reset] &7Your progress was reset. Starting over from rank A!");
            Bukkit.getScheduler().runTaskLater(plugin, () -> applyGear(on), 60L);
        } else if (on != null) {
            Util.tell(on, "&#A3E635[Reset] &7Your " + scope + " progress was reset.");
        }
        Util.tell(s, "&a" + n + " reset (" + scope + "). It runs in the background.");
    }

    public void resetServer(CommandSender s, String scopeWord, String confirmWord) {
        String scope = scopeWord == null ? "all" : scopeWord.toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) { Util.tell(s, "&e/mobzone reset server [prison|oneblock|smp|all] [confirm]"); return; }
        if (!confirmed(s, "server:" + scope, confirmWord,
                "&c&lRESET THE SERVER? This wipes every player's " + describe(scope) + ". Staff and donor ranks stay. It cannot be undone.\n"
                        + "&cMake a backup first (world folders, plugins/ and /lp export backup).\n"
                        + "&cType &f/mobzone reset server " + scope + " confirm &cwithin 30 seconds. Everyone online will be kicked.")) return;
        List<String> all = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() == null || !seen.add(op.getUniqueId())) continue;
            all.addAll(planFor(op.getName(), op.getUniqueId(), scope));
            applyPluginState(op.getUniqueId(), scope);
        }
        if (has(scope, "prison")) {
            all.add(0, "minecraft:scoreboard players reset * kills");
            plugin.chests().resetAll();
            plugin.quests().resetAll();
        }
        if (scope.equals("all")) plugin.wallets().resetAll();
        save();
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers()))
            if (p != s) p.kick(Util.c("&aServer reset in progress.\n&7Rejoin in a minute to start fresh!"));
        enqueue(all);
        Util.tell(s, "&aReset (" + scope + ") started for " + seen.size() + " players. It runs in the background (about " + (all.size() / Math.max(1, plugin.getConfig().getInt("reset.commands-per-tick", 8)) / 20 + 1)
                + " seconds). Prison gear and bonuses reset when each player next enters the prison.");
    }

    // ---------------------------------------------------------------- applied when the player is in prison

    private void applyGear(Player p) {
        if (!p.isOnline() || !pendingGear.contains(p.getUniqueId())) return;
        if (!p.getWorld().getName().equalsIgnoreCase("prison")) return;
        if (!queue.isEmpty()) { Bukkit.getScheduler().runTaskLater(plugin, () -> applyGear(p), 40L); return; }   // wait until the wipe commands finished
        pendingGear.remove(p.getUniqueId());
        save();
        String n = p.getName();
        Util.console("minecraft:clear " + n);
        Util.console("minecraft:attribute " + n + " minecraft:max_health modifier remove prison:dragon_heart");
        Util.console("minecraft:attribute " + n + " minecraft:movement_speed modifier remove prison:speed");
        p.setFoodLevel(20);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) GearRestore.run(p, "all"); }, 60L);
    }

    private void applyOb(Player p) {
        if (!p.isOnline() || !pendingOb.contains(p.getUniqueId())) return;
        if (!p.getWorld().getName().toLowerCase(Locale.ROOT).startsWith("oneblock")) return;
        if (!queue.isEmpty()) { Bukkit.getScheduler().runTaskLater(plugin, () -> applyOb(p), 40L); return; }
        pendingOb.remove(p.getUniqueId());
        save();
        p.getInventory().clear();
        p.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
        p.getEnderChest().clear();
        p.setLevel(0);
        p.setExp(0f);
        p.setFoodLevel(20);
        p.setHealth(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null ? p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() : 20.0);
        Util.tell(p, "&#A3E635[Reset] &7Your Oneblock inventory was cleared.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> applyGear(e.getPlayer()), 100L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> applyOb(e.getPlayer()), 100L);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> applyGear(e.getPlayer()), 40L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> applyOb(e.getPlayer()), 40L);
    }
}
