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

    private static String tag(int role) {
        return switch (role) {
            case 5 -> "&4&l[Owner] &f";
            case 4 -> "&c&l[Co-Owner] &f";
            case 3 -> "&6&l[Manager] &f";
            case 2 -> "&c[Admin] &f";
            case 1 -> "&9[Mod] &f";
            case 0 -> "&a[Helper] &f";
            default -> "&7[Member] &7";
        };
    }

    /** Chat is "[Staff role] Name: message". Rank, prestige and any other plugin's prefix are never shown (unless chat.show-prestige is true). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!enabled) return;
        boolean showPrestige = plugin.getConfig().getBoolean("chat.show-prestige", false);
        e.renderer(ChatRenderer.viewerUnaware((Player source, Component name, Component msg) -> {
            String p = tag(StaffCommands.roleOf(source));
            if (showPrestige) {
                var perms = RankBonus.held(source);
                int pr = RankBonus.prestigeLevel(source, perms);
                if (pr > 0) p = "&#4ADE80[" + titles.get(Math.min(pr, titles.size()) - 1) + "] " + p;
            }
            return LEGACY.deserialize(p + source.getName())
                    .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                    .append(msg.colorIfAbsent(NamedTextColor.WHITE));
        }));
    }
}
