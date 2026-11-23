package dev.mobzone;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SMP random teleport menu (Overworld / Nether / End) and the Nether/End on-off switch.
 * Config (rtp section): worlds, radius, cooldown. Switch with /mobzone dimension <nether|end> <on|off> (saved in dimensions.yml).
 */
public final class Rtp implements Listener, CommandExecutor {
    private final MobZonePlugin plugin;
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private boolean netherOn = true, endOn = true;

    private static final class RtpH implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static final Set<Material> BAD = EnumSet.of(Material.LAVA, Material.WATER, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS,
            Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
            Material.BEDROCK, Material.WITHER_ROSE, Material.POINTED_DRIPSTONE, Material.COBWEB);

    public Rtp(MobZonePlugin plugin) { this.plugin = plugin; }

    public void reload() {
        var y = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "dimensions.yml"));
        netherOn = y.getBoolean("nether", true);
        endOn = y.getBoolean("end", true);
    }

    public boolean setDimension(String which, boolean on) {
        if (which.equals("nether")) netherOn = on; else if (which.equals("end")) endOn = on; else return false;
        var y = new YamlConfiguration();
        y.set("nether", netherOn); y.set("end", endOn);
        try { y.save(new File(plugin.getDataFolder(), "dimensions.yml")); } catch (Exception ex) { plugin.getLogger().warning("dimensions.yml: " + ex.getMessage()); }
        return true;
    }

    public String dimensionStatus() { return "Nether " + (netherOn ? "ON" : "OFF") + ", End " + (endOn ? "ON" : "OFF"); }

    private String worldName(String dim) {
        return plugin.getConfig().getString("rtp.worlds." + dim, dim.equals("overworld") ? "smp" : dim.equals("nether") ? "smp_nether" : "smp_the_end");
    }

    // ------------------------------------------------------------ command + menu

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) { Util.tell(s, "&cPlayers only."); return true; }
        if (!"smp".equals(Market.modeOf(p.getWorld().getName()))) { Util.tell(p, "&#A3E635[RTP] &cThis only works in the SMP."); return true; }
        if (a.length > 0) {
            String d = a[0].toLowerCase(Locale.ROOT);
            if (d.equals("overworld") || d.equals("nether") || d.equals("end")) { start(p, d); return true; }
        }
        open(p);
        return true;
    }

    private ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Util.c(name).decoration(org.bukkit.format.TextDecoration.ITALIC, false));
        List<net.kyori.adventure.text.Component> ls = new ArrayList<>();
        for (String x : lore) ls.add(Util.c(x).decoration(org.bukkit.format.TextDecoration.ITALIC, false));
        meta.lore(ls);
        it.setItemMeta(meta);
        return it;
    }

    private void open(Player p) {
        RtpH h = new RtpH();
        h.inv = Bukkit.createInventory(h, 27, Util.c("&8Random Teleport"));
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) h.inv.setItem(i, pane);
        h.inv.setItem(11, icon(Material.GRASS_BLOCK, "&aOverworld", "&7Teleport to a random spot", "&eClick to go"));
        h.inv.setItem(13, netherOn ? icon(Material.NETHERRACK, "&cNether", "&7Teleport to a random spot", "&eClick to go")
                : icon(Material.BARRIER, "&cNether is disabled", "&7An admin turned it off"));
        h.inv.setItem(15, endOn ? icon(Material.END_STONE, "&dThe End", "&7Teleport to a random spot", "&eClick to go")
                : icon(Material.BARRIER, "&cThe End is disabled", "&7An admin turned it off"));
        p.openInventory(h.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof RtpH)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        switch (e.getRawSlot()) {
            case 11 -> { p.closeInventory(); start(p, "overworld"); }
            case 13 -> { p.closeInventory(); start(p, "nether"); }
            case 15 -> { p.closeInventory(); start(p, "end"); }
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof RtpH) e.setCancelled(true);
    }

    // ------------------------------------------------------------ teleport

    private void start(Player p, String dim) {
        String tag = "&#A3E635[RTP] ";
        if (dim.equals("nether") && !netherOn) { Util.tell(p, tag + "&cThe Nether is disabled right now."); return; }
        if (dim.equals("end") && !endOn) { Util.tell(p, tag + "&cThe End is disabled right now."); return; }
        World w = Bukkit.getWorld(worldName(dim));
        if (w == null) { Util.tell(p, tag + "&cThat world isn't loaded (" + worldName(dim) + "). Tell an admin."); return; }
        if (busy.contains(p.getUniqueId())) return;
        int cd = plugin.getConfig().getInt("rtp.cooldown-seconds", 30);
        long now = System.currentTimeMillis();
        Long until = cooldown.get(p.getUniqueId());
        if (until != null && until > now && !p.hasPermission("mobzone.admin")) {
            Util.tell(p, tag + "&cWait " + ((until - now + 999) / 1000) + "s before using /rtp again.");
            return;
        }
        busy.add(p.getUniqueId());
        Util.tell(p, tag + "&7Finding a safe spot...");
        attempt(p, w, dim, 0, cd);
    }

    private void attempt(Player p, World w, String dim, int tries, int cd) {
        if (!p.isOnline()) { busy.remove(p.getUniqueId()); return; }
        if (tries >= 15) {
            busy.remove(p.getUniqueId());
            Util.tell(p, "&#A3E635[RTP] &cCouldn't find a safe spot. Try again.");
            return;
        }
        int max = plugin.getConfig().getInt("rtp.radius." + dim, dim.equals("overworld") ? 5000 : dim.equals("nether") ? 2000 : 2500);
        int min = dim.equals("end") ? Math.min(1000, max / 2) : 0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = r.nextDouble() * Math.PI * 2;
        double dist = min + r.nextDouble() * Math.max(1, max - min);
        int x = (int) (Math.cos(ang) * dist), z = (int) (Math.sin(ang) * dist);
        if (!w.getWorldBorder().isInside(new Location(w, x, 64, z))) { attempt(p, w, dim, tries + 1, cd); return; }
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(ch -> {
            Location loc = safe(w, dim, x, z);
            if (loc == null) { attempt(p, w, dim, tries + 1, cd); return; }
            if (!p.isOnline()) { busy.remove(p.getUniqueId()); return; }
            p.teleportAsync(loc, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok -> {
                busy.remove(p.getUniqueId());
                if (ok) {
                    cooldown.put(p.getUniqueId(), System.currentTimeMillis() + cd * 1000L);
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    Util.tell(p, "&#A3E635[RTP] &7Teleported to &f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "&7.");
                } else Util.tell(p, "&#A3E635[RTP] &cTeleport failed. Try again.");
            });
        });
    }

    private Location safe(World w, String dim, int x, int z) {
        int y;
        if (dim.equals("nether")) {
            y = -1;
            for (int yy = 120; yy > 33; yy--) {
                var feet = w.getBlockAt(x, yy, z); var head = w.getBlockAt(x, yy + 1, z); var floor = w.getBlockAt(x, yy - 1, z);
                if (feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid() && floor.getType().isSolid() && !BAD.contains(floor.getType())) { y = yy; break; }
            }
            if (y < 0) return null;
        } else {
            y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            var floor = w.getBlockAt(x, y - 1, z);
            if (y <= w.getMinHeight() + 1 || !floor.getType().isSolid() || BAD.contains(floor.getType())) return null;
            if (!w.getBlockAt(x, y, z).isPassable() || !w.getBlockAt(x, y + 1, z).isPassable()) return null;
            if (dim.equals("end") && y < 10) return null;
        }
        return new Location(w, x + 0.5, y, z + 0.5);
    }

    // ------------------------------------------------------------ dimension switch

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (!"smp".equals(Market.modeOf(e.getFrom().getWorld().getName()))) return;
        var cause = e.getCause();
        boolean nether = cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL;
        boolean end = cause == PlayerTeleportEvent.TeleportCause.END_PORTAL || cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY;
        Location to = e.getTo();
        if (to != null && to.getWorld() != null) {
            var env = to.getWorld().getEnvironment();
            nether = nether && env == World.Environment.NETHER;
            end = end && env == World.Environment.THE_END;
        }
        if ((nether && !netherOn) || (end && !endOn)) {
            e.setCancelled(true);
            Util.tell(e.getPlayer(), "&#A3E635[SMP] &c" + (nether ? "The Nether" : "The End") + " is disabled right now.");
        }
    }
}
