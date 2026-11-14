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
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Map<String, Long> confirms = new HashMap<>();
    private File file;
    private int taskId = -1;

    public ResetManager(MobZonePlugin plugin) { this.plugin = plugin; }

    // ---------------------------------------------------------------- storage

    public void reload() {
        file = new File(plugin.getDataFolder(), "resets.yml");
        pendingGear.clear();
        var y = YamlConfiguration.loadConfiguration(file);
        for (String u : y.getStringList("pending")) {
            try { pendingGear.add(UUID.fromString(u)); } catch (IllegalArgumentException ignored) { }
        }
    }

    private void save() {
        var y = new YamlConfiguration();
        List<String> l = new ArrayList<>();
        for (UUID u : pendingGear) l.add(u.toString());
        y.set("pending", l);
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
    private List<String> commandsFor(String n, UUID id) {
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
        c.add("eco reset " + n);
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

    public void resetPlayer(CommandSender s, String name, String confirmWord) {
        OfflinePlayer t = Bukkit.getPlayerExact(name);
        if (t == null) {
            t = Bukkit.getOfflinePlayerIfCached(name);
            if (t == null || !t.hasPlayedBefore()) { Util.tell(s, "&cI don't know a player called " + name + "."); return; }
        }
        String n = t.getName() == null ? name : t.getName();
        if (!confirmed(s, "p:" + n.toLowerCase(Locale.ROOT), confirmWord,
                "&e&lThis wipes ALL progress for " + n + " (kills, gates, rank, prestige, upgrades, tokens, cash, keys, quests, gear). Staff and donor ranks stay.\n"
                        + "&eType &f/mobzone resetplayer " + n + " confirm &ewithin 30 seconds to do it.")) return;
        doPlayer(t, n);
        Util.tell(s, "&a" + n + " reset. " + (t.isOnline() ? "Their gear and bonuses are reset in the prison world." : "Their gear and bonuses reset the next time they join the prison."));
    }

    private void doPlayer(OfflinePlayer t, String n) {
        enqueue(commandsFor(n, t.getUniqueId()));
        plugin.chests().resetPlayer(t.getUniqueId());
        plugin.quests().resetPlayer(t.getUniqueId());
        pendingGear.add(t.getUniqueId());
        save();
        Player on = t.getPlayer();
        if (on != null) {
            Util.tell(on, "&#A3E635[Reset] &7Your progress was reset. Starting over from rank A!");
            Bukkit.getScheduler().runTaskLater(plugin, () -> applyGear(on), 60L);
        }
    }

    public void resetServer(CommandSender s, String confirmWord) {
        if (!confirmed(s, "server", confirmWord,
                "&c&lRESET THE WHOLE SERVER? This wipes every player's kills, gates, rank, prestige, upgrades, tokens, cash, keys, quests and gear (staff and donor ranks stay). It cannot be undone.\n"
                        + "&cMake a backup first (world folders, plugins/ and /lp export backup).\n"
                        + "&cType &f/mobzone reset server confirm &cwithin 30 seconds. Everyone online will be kicked.")) return;
        List<String> all = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        List<OfflinePlayer> players = new ArrayList<>(List.of(Bukkit.getOfflinePlayers()));
        for (OfflinePlayer op : players) {
            if (op.getName() == null || !seen.add(op.getUniqueId())) continue;
            all.addAll(commandsFor(op.getName(), op.getUniqueId()));
            pendingGear.add(op.getUniqueId());
        }
        all.add(0, "minecraft:scoreboard players reset * kills");
        plugin.chests().resetAll();
        plugin.quests().resetAll();
        save();
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers()))
            if (p != s) p.kick(Util.c("&aServer reset in progress.\n&7Rejoin in a minute to start fresh!"));
        enqueue(all);
        Util.tell(s, "&aReset started for " + seen.size() + " players. It runs in the background (about " + (all.size() / Math.max(1, plugin.getConfig().getInt("reset.commands-per-tick", 8)) / 20 + 1)
                + " seconds). Gear and bonuses reset when each player next enters the prison.");
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

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> applyGear(e.getPlayer()), 100L);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> applyGear(e.getPlayer()), 40L);
    }
}
