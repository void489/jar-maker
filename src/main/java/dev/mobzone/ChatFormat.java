package dev.mobzone;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;

/** Chat line: "[Prestige] [Rank] Name: message". Rank and prestige come from LuckPerms groups. */
public class ChatFormat implements Listener {
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();
    private final MobZonePlugin plugin;
    private boolean enabled;
    private String prefix;
    private List<String> titles;

    public ChatFormat(MobZonePlugin plugin) { this.plugin = plugin; }

    public void reload() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("chat.enabled", true);
        prefix = c.getString("chat.format", "&#4ADE80{prestige}");
        titles = c.getStringList("chat.prestige-titles");
        if (titles.isEmpty()) titles = List.of("Survivor", "Scavenger", "Hunter", "Slayer", "Warden",
                "Reaper", "Warlord", "Overlord", "Immortal", "Apex");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!enabled) return;
        var perms = RankBonus.held(e.getPlayer());
        int rank = Math.max(1, RankBonus.rank(e.getPlayer(), perms));
        int pr = RankBonus.prestigeLevel(e.getPlayer(), perms);
        String p = prefix.replace("{rank}", String.valueOf((char) ('A' + rank - 1)))
                .replace("{prestige}", pr > 0 ? "[" + titles.get(Math.min(pr, titles.size()) - 1) + "] " : "");
        Component pre = LEGACY.deserialize(p);
        e.renderer(ChatRenderer.viewerUnaware((Player source, Component name, Component msg) ->
                pre.append(name).append(Component.text(": ", NamedTextColor.DARK_GRAY)).append(msg.colorIfAbsent(NamedTextColor.WHITE))));
    }
}
