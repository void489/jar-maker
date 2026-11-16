package dev.mobzone.hover;

import com.github.retrooper.packetevents.PacketEvents;
import dev.mobzone.Market;
import dev.mobzone.MobZonePlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Optional add-on for MobZone: adds a "Worth" line to item tooltips (client-side only, via PacketEvents).
 * The real items are never changed. Players in the economy modes see it; creative players don't.
 * Delete MobZoneHover.jar to turn it off.
 */
public final class HoverPlugin extends JavaPlugin implements org.bukkit.event.Listener {
    // snapshots written on the main thread, read on network threads
    volatile Map<UUID, String> modes = Map.of();
    volatile Map<String, Map<Material, Double>> worth = Map.of();
    volatile Map<String, Map<Material, Long>> orders = Map.of();
    private HoverListener listener;

    /** /hoverdebug: logs the next 25 tooltip decisions to the console. */
    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (listener != null) listener.debug(25);
        sender.sendMessage("Hover debug: the next 25 decisions will be logged to the console. Open your inventory or switch items now.");
        return true;
    }

    @Override
    public void onEnable() {
        if (!(Bukkit.getPluginManager().getPlugin("MobZone") instanceof MobZonePlugin mz)) {
            getLogger().warning("MobZone not found, hover tooltips are off.");
            return;
        }
        Market market = mz.market();
        refresh(market);
        Bukkit.getScheduler().runTaskTimer(this, () -> refresh(market), 10L, 10L);
        Bukkit.getPluginManager().registerEvents(this, this);
        listener = new HoverListener(this);
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        getLogger().info("Hover tooltips on.");
    }

    // The server only re-sends items it thinks changed, so after moving/picking up/dropping items the tooltip text would be lost.
    // Re-send the whole inventory one tick after any change (through the packet listener, so the tooltips come back).
    private final java.util.Set<UUID> resend = new java.util.HashSet<>();

    private void queue(org.bukkit.entity.Entity h) {
        if (!(h instanceof Player p) || !modes.containsKey(p.getUniqueId())) return;
        if (!resend.add(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(this, () -> {
            resend.remove(p.getUniqueId());
            if (p.isOnline()) p.updateInventory();
        });
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onClick(org.bukkit.event.inventory.InventoryClickEvent e) { queue(e.getWhoClicked()); }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent e) { queue(e.getWhoClicked()); }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) { queue(e.getPlayer()); }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) { queue(e.getEntity()); }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent e) { queue(e.getPlayer()); }

    private void refresh(Market market) {
        Map<UUID, String> old = modes;
        Map<UUID, String> m = new HashMap<>();
        Map<String, Map<Material, Double>> w = new HashMap<>();
        Map<String, Map<Material, Long>> o = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            String mode = market.modeFor(p);
            if (mode == null || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            m.put(p.getUniqueId(), mode);
            w.computeIfAbsent(mode, market::worthSnapshot);
            o.computeIfAbsent(mode, market::bestOrderSnapshot);
        }
        modes = m; worth = w; orders = o;
        // tooltips only apply when the inventory is (re)sent, so resend it when a player's tooltip state changed
        for (Player p : Bukkit.getOnlinePlayers()) {
            String before = old.get(p.getUniqueId()), after = m.get(p.getUniqueId());
            if (!java.util.Objects.equals(before, after)) p.updateInventory();
        }
    }
}
