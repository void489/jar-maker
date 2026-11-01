package dev.mobzone;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /quests and /events for everyone. */
public class PlayerCommands implements CommandExecutor {
    private final MobZonePlugin plugin;

    public PlayerCommands(MobZonePlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("quests")) {
            if (sender instanceof Player p) plugin.quests().show(p);
            else Util.tell(sender, "&cPlayers only.");
        } else {
            Util.tell(sender, plugin.events().status());
        }
        return true;
    }
}
