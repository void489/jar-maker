package dev.mobzone;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Small prison rules: no hunger loss, the starter sword can't be dropped/moved/lost on death,
 * and zombies never come with chicken jockeys. Settings are read with defaults, so old config files keep working.
 */
public final class PrisonRules implements Listener {
    private final MobZonePlugin plugin;
    private final Map<UUID, List<ItemStack>> kept = new HashMap<>();
    private final Map<UUID, Long> menuCooldown = new HashMap<>();
    private boolean cleaving;

    public PrisonRules(MobZonePlugin plugin) { this.plugin = plugin; }

    private boolean worldOn(String key, org.bukkit.World w) {
        if (w == null || !plugin.getConfig().getBoolean(key + ".enabled", true)) return false;
        List<String> l = plugin.getConfig().getStringList(key + ".worlds");
        if (l.isEmpty()) l = List.of("prison");
        for (String s : l) if (s.equalsIgnoreCase(w.getName())) return true;
        return false;
    }

    private boolean locked(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return false;
        var meta = it.getItemMeta();
        if (!meta.hasDisplayName() || meta.displayName() == null) return false;
        String name = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).toLowerCase(Locale.ROOT);
        List<String> names = plugin.getConfig().getStringList("locked-items.names");
        if (names.isEmpty()) names = List.of("Prison Blade");
        for (String n : names) if (name.contains(n.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private boolean bypass(Player p) { return p.hasPermission("mobzone.bypasslock"); }

    // ---- hunger never drops
    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p) || !worldOn("no-hunger", p.getWorld())) return;
        if (e.getFoodLevel() < p.getFoodLevel()) {
            e.setCancelled(true);
            p.setFoodLevel(20);
            p.setSaturation(20f);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (plugin.getConfig().getBoolean("hide-join-quit", true)) e.joinMessage(null);
        if (worldOn("no-hunger", p.getWorld())) { p.setFoodLevel(20); p.setSaturation(20f); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        if (plugin.getConfig().getBoolean("hide-join-quit", true)) e.quitMessage(null);
    }

    // ---- sword lock
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!bypass(e.getPlayer()) && locked(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (bypass(e.getPlayer())) return;
        if (locked(e.getMainHandItem()) || locked(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || bypass(p)) return;
        boolean hit = locked(e.getCurrentItem()) || locked(e.getCursor());
        if (!hit && e.getHotbarButton() >= 0) hit = locked(p.getInventory().getItem(e.getHotbarButton()));
        if (!hit && e.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND) hit = locked(p.getInventory().getItemInOffHand());
        if (hit) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || bypass(p)) return;
        if (locked(e.getOldCursor())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        List<ItemStack> mine = new ArrayList<>();
        e.getDrops().removeIf(it -> { if (locked(it)) { mine.add(it.clone()); return true; } return false; });
        if (!mine.isEmpty()) kept.put(e.getPlayer().getUniqueId(), mine);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        List<ItemStack> mine = kept.remove(e.getPlayer().getUniqueId());
        if (mine == null) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (ItemStack it : mine) e.getPlayer().getInventory().addItem(it);
        });
    }

    // ---- no chicken jockeys
    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!worldOn("no-chicken-jockeys", e.getLocation().getWorld())) return;
        if (e.getEntity() instanceof Chicken && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.JOCKEY) {
            e.setCancelled(true);
            return;
        }
        if (e.getEntity() instanceof Zombie z) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!z.isValid()) return;
                for (Entity pass : new ArrayList<>(z.getPassengers()))
                    if (pass instanceof Chicken) { z.removePassenger(pass); pass.remove(); }
                if (z.getVehicle() instanceof Chicken c) { z.leaveVehicle(); c.remove(); }
            });
        }
    }

    // ---- right-click the Prison Blade = open the sword enchant menu
    @EventHandler
    public void onRightClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (p.isSneaking() || !locked(p.getInventory().getItemInMainHand())) return;
        if (!plugin.getConfig().getBoolean("sword-menu.enabled", true)) return;
        long now = System.currentTimeMillis();
        if (now - menuCooldown.getOrDefault(p.getUniqueId(), 0L) < 800) return;
        menuCooldown.put(p.getUniqueId(), now);
        p.performCommand(plugin.getConfig().getString("sword-menu.command", "blade"));
    }

    // ---- cleave: the Prison Blade also hits up to 2 nearby monsters for half damage
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (cleaving || !(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof Monster hit)) return;
        if (!plugin.getConfig().getBoolean("cleave.enabled", true) || !worldOn("cleave", p.getWorld())) return;
        if (!locked(p.getInventory().getItemInMainHand())) return;
        double radius = plugin.getConfig().getDouble("cleave.radius", 3.0);
        int extra = plugin.getConfig().getInt("cleave.extra-targets", 2);
        double share = plugin.getConfig().getDouble("cleave.damage-share", 0.5);
        double dmg = e.getFinalDamage() * share;
        if (dmg <= 0) return;
        cleaving = true;
        try {
            int n = 0;
            for (Entity ent : hit.getNearbyEntities(radius, 1.5, radius)) {
                if (n >= extra) break;
                if (ent instanceof Monster m && m.isValid() && !m.equals(hit)) { m.damage(dmg, p); n++; }
            }
        } finally { cleaving = false; }
    }
}
