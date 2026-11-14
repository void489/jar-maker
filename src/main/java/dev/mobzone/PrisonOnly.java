package dev.mobzone;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.List;
import java.util.Locale;

/**
 * Prison commands only work in the prison world. Everywhere else the command is cancelled with a message.
 * Players with mobzone.bypass.worldcommands can use them anywhere.
 * Config (all optional, in-code defaults shown):
 *   prison-only:
 *     enabled: true
 *     worlds: [prison]
 *     commands: [rankup, ranks, prestige, ...]
 *     warps: [starter, cellblock, ...]       # /warp <name> only works from inside the prison
 *     message: '&cThat only works in the prison.'
 */
public final class PrisonOnly implements Listener {
    private static final List<String> COMMANDS = List.of("rankup", "rankupmax", "ranks", "rank", "prestige", "prestiges",
            "enchants", "enchant", "armor", "upgrades", "blade", "armorup", "perks", "quests", "guide", "prisonhelp",
            "sell", "autosell", "mines", "bounty");
    private static final List<String> WARPS = List.of("starter", "cellblock", "cafeteria", "laundry", "infirmary", "yard",
            "sewers", "lab", "quarantine", "subbasement");
    private static final List<String> WARP_CMDS = List.of("warp", "warps", "ewarp", "ewarps");

    private final MobZonePlugin plugin;

    public PrisonOnly(MobZonePlugin plugin) { this.plugin = plugin; }

    private List<String> list(String path, List<String> def) {
        List<String> l = plugin.getConfig().getStringList(path);
        return l.isEmpty() ? def : l;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!plugin.getConfig().getBoolean("prison-only.enabled", true)) return;
        Player p = e.getPlayer();
        if (p.hasPermission("mobzone.bypass.worldcommands") && !p.isOp()) return;   // explicit permission only
        for (String w : list("prison-only.worlds", List.of("prison"))) if (w.equalsIgnoreCase(p.getWorld().getName())) return;

        String raw = e.getMessage().trim();
        if (raw.startsWith("/")) raw = raw.substring(1);
        String[] parts = raw.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return;
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);

        boolean block = list("prison-only.commands", COMMANDS).contains(cmd);
        if (!block && WARP_CMDS.contains(cmd) && parts.length > 1)
            block = list("prison-only.warps", WARPS).contains(parts[1].toLowerCase(Locale.ROOT));
        if (!block) return;
        e.setCancelled(true);
        Util.tell(p, plugin.getConfig().getString("prison-only.message", "&cThat only works in the prison. Use &f/warp prison &cfirst."));
    }
}
