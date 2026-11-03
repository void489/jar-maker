package dev.mobzone;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;

/** First-join welcome: a title, a few chat lines, and the /guide menu opens by itself. */
public class JoinWelcome implements Listener {
    private final MobZonePlugin plugin;

    public JoinWelcome(MobZonePlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        var c = plugin.getConfig();
        if (!c.getBoolean("welcome.enabled", true)) return;
        Player p = e.getPlayer();
        if (p.hasPlayedBefore()) return;
        List<String> lines = c.getStringList("welcome.messages");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.showTitle(Title.title(Util.c(c.getString("welcome.title", "&#A3E635&lWELCOME")),
                    Util.c(c.getString("welcome.subtitle", "&7Survive the Dead Cell Block")), Title.DEFAULT_TIMES));
            for (String l : lines) p.sendMessage(Util.c(l.replace("{player}", p.getName())));
            if (c.getBoolean("welcome.open-guide", true)) Util.console("dm open guide " + p.getName());
        }, 60L);
    }
}
