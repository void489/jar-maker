package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Separate cash per game mode, on top of the single Essentials economy.
 * Essentials only keeps ONE balance per player, so the balance is swapped when a player moves between modes:
 * the old mode's cash is saved to plugins/MobZone/wallets.yml and the new mode's cash is loaded with /eco set.
 * Worlds that belong to no mode (the lobby) keep whatever wallet is active.
 * Config (in-code defaults):   wallets: { enabled: true, worlds: { prison: prison, oneblock_world: oneblock }, start: { oneblock: 0 } }
 * Any world starting with "oneblock" is the oneblock wallet.
 */
public final class WalletManager implements Listener {
    private final MobZonePlugin plugin;
    private final Map<UUID, String> active = new HashMap<>();
    private final Map<UUID, Map<String, Long>> saved = new HashMap<>();
    private File file;

    public WalletManager(MobZonePlugin plugin) { this.plugin = plugin; }

    // ---------------------------------------------------------------- storage

    public void reload() {
        file = new File(plugin.getDataFolder(), "wallets.yml");
        active.clear();
        saved.clear();
        var y = YamlConfiguration.loadConfiguration(file);
        var root = y.getConfigurationSection("players");
        if (root == null) return;
        for (String u : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(u);
                var sec = root.getConfigurationSection(u);
                if (sec == null) continue;
                String a = sec.getString("active");
                if (a != null) active.put(id, a);
                var b = sec.getConfigurationSection("balances");
                if (b != null) {
                    Map<String, Long> m = new HashMap<>();
                    for (String g : b.getKeys(false)) m.put(g, b.getLong(g));
                    saved.put(id, m);
                }
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void save() {
        var y = new YamlConfiguration();
        for (UUID id : active.keySet()) y.set("players." + id + ".active", active.get(id));
        for (var e : saved.entrySet()) for (var b : e.getValue().entrySet()) y.set("players." + e.getKey() + ".balances." + b.getKey(), b.getValue());
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save wallets.yml: " + ex.getMessage()); }
    }

    public void resetPlayer(UUID id) { active.remove(id); saved.remove(id); save(); }

    public void resetAll() { active.clear(); saved.clear(); save(); }

    /** Forgets one mode's saved cash. Returns a console command if that mode's cash is the live Essentials balance. */
    public String resetMode(UUID id, String mode, String name) {
        var m = saved.get(id);
        if (m != null) m.remove(mode);
        String cmd = null;
        if (mode.equals(active.getOrDefault(id, "prison")))
            cmd = "eco set " + name + " " + plugin.getConfig().getLong("wallets.start." + mode, mode.equals("smp") ? 100L : 0L);
        save();
        return cmd;
    }

    // ---------------------------------------------------------------- logic

    private boolean enabled() { return plugin.getConfig().getBoolean("wallets.enabled", true); }

    private String groupOf(String world) {
        var sec = plugin.getConfig().getConfigurationSection("wallets.worlds");
        if (sec != null) for (String k : sec.getKeys(false)) if (k.equalsIgnoreCase(world)) return sec.getString(k);
        String w = world.toLowerCase(Locale.ROOT);
        if (w.equals("prison")) return "prison";
        if (w.startsWith("oneblock")) return "oneblock";
        if (w.startsWith("smp")) return "smp";
        return null;
    }

    static long readBalance(Player p) {
        for (String ph : new String[]{"%vault_eco_balance_fixed%", "%vault_eco_balance%"}) {
            String raw = RankBonus.placeholder(p, ph);
            double v = RankNotifier.num(raw);
            if (!Double.isNaN(v)) return Math.round(v);
        }
        return Long.MIN_VALUE;
    }

    private void sync(Player p) {
        if (!enabled() || !p.isOnline()) return;
        String g = groupOf(p.getWorld().getName());
        if (g == null) return;
        UUID id = p.getUniqueId();
        String a = active.getOrDefault(id, "prison");          // cash from before this feature belongs to the prison
        if (a.equals(g)) { if (!active.containsKey(id)) { active.put(id, g); save(); } return; }
        long cur = readBalance(p);
        if (cur == Long.MIN_VALUE) { plugin.getLogger().warning("Wallet swap skipped for " + p.getName() + ": couldn't read the balance (is PlaceholderAPI's Vault expansion installed?)"); return; }
        var m = saved.computeIfAbsent(id, k -> new HashMap<>());
        m.put(a, cur);
        long next = m.getOrDefault(g, plugin.getConfig().getLong("wallets.start." + g, g.equals("smp") ? 100L : 0L));
        active.put(id, g);
        save();
        Util.console("eco set " + p.getName() + " " + next);
        Util.tell(p, "&#A3E635[Cash] &7You are now using your &f" + g + " &7cash: &a$" + Util.money(next));
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> sync(p), 5L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> sync(p), 40L);
    }
}
