package dev.mobzone;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** %mobzone_rank% (rank letter, from Prison or the LuckPerms rank groups), %mobzone_keys_<crate>% and %mobzone_keys_total% for holograms, scoreboards and menus. */
final class KeysExpansion extends PlaceholderExpansion {
    private final MobZonePlugin plugin;

    KeysExpansion(MobZonePlugin plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "mobzone"; }
    @Override public String getAuthor() { return "MobZone"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return null;
        if (params.equalsIgnoreCase("rank")) {
            var online = player.getPlayer();
            if (online == null) return "";
            int r = RankBonus.rank(online, RankBonus.held(online));
            return r < 1 ? "None" : String.valueOf((char) ('A' + r - 1));
        }
        if (!params.toLowerCase().startsWith("keys_")) return null;
        String crate = params.substring(5).toLowerCase();
        if (crate.equals("total")) {
            int sum = 0;
            for (String id : plugin.crates().ids()) sum += plugin.chests().keysOf(player.getUniqueId(), id);
            return String.valueOf(sum);
        }
        return String.valueOf(plugin.chests().keysOf(player.getUniqueId(), crate));
    }
}
