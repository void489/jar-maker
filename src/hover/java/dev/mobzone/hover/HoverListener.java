package dev.mobzone.hover;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Adds "Worth: $X each" to the lore of sellable plain items as they are sent to the player. */
final class HoverListener extends PacketListenerAbstract {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private final HoverPlugin plugin;
    private boolean warned;

    HoverListener(HoverPlugin plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        try {
            if (event.getUser() == null || event.getUser().getUUID() == null) return;
            String mode = plugin.modes.get(event.getUser().getUUID());
            if (mode == null) { dbg("skip: player " + event.getUser().getName() + " is not active (wrong world, creative, or not in an economy mode)"); return; }
            if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                WrapperPlayServerSetSlot w = new WrapperPlayServerSetSlot(event);
                if (w.getWindowId() < 0) return;                                  // cursor item: leave alone
                ItemStack n = tag(w.getItem(), mode);
                if (n != w.getItem()) { w.setItem(n); event.markForReEncode(true); }
            } else if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                WrapperPlayServerWindowItems w = new WrapperPlayServerWindowItems(event);
                List<ItemStack> in = w.getItems();
                List<ItemStack> out = new ArrayList<>(in.size());
                boolean changed = false;
                for (ItemStack it : in) {
                    ItemStack n = tag(it, mode);
                    if (n != it) changed = true;
                    out.add(n);
                }
                if (changed) { w.setItems(out); event.markForReEncode(true); }
            }
        } catch (RuntimeException ex) {
            // never break packets because of a tooltip; log the first problem so it can be fixed
            if (!warned) { warned = true; plugin.getLogger().warning("Hover tooltip error (shown once): " + ex); ex.printStackTrace(); }
        }
    }

    private ItemStack tag(ItemStack it, String mode) {
        if (it == null || it.isEmpty()) return it;
        // menus and named items are left alone. Plain items may carry an EMPTY default lore, so only real lore counts.
        var oldLore = it.getComponent(ComponentTypes.LORE);
        if (oldLore.isPresent() && !oldLore.get().getLines().isEmpty()) return it;
        if (it.getComponent(ComponentTypes.CUSTOM_NAME).isPresent()) return it;
        Material m = SpigotConversionUtil.toBukkitItemMaterial(it.getType());
        if (m == null) return it;
        Map<Material, Double> w = plugin.worth.getOrDefault(mode, Map.of());
        Map<Material, Long> o = plugin.orders.getOrDefault(mode, Map.of());
        double base = w.getOrDefault(m, 0.0);
        long best = o.getOrDefault(m, 0L);
        double each = Math.max(base, best);
        if (each <= 0) { dbg("skip: no worth for " + m); return it; }
        dbg("tagged " + m + " worth " + each);
        List<Component> lore = new ArrayList<>();
        lore.add(line("&a$" + fmt(each * Math.max(1, it.getAmount()))));      // one item: its worth; a stack: the total
        ItemStack copy = it.copy();
        copy.setComponent(ComponentTypes.LORE, new ItemLore(lore));
        return copy;
    }

    private int debugLeft;
    void debug(int n) { debugLeft = n; }
    private void dbg(String s) { if (debugLeft > 0) { debugLeft--; plugin.getLogger().info("[debug] " + s); } }

    private static Component line(String s) { return LEGACY.deserialize(s).decoration(TextDecoration.ITALIC, false); }

    private static String fmt(double v) {
        if (v == Math.rint(v)) return String.format(Locale.US, "%,d", (long) v);
        return String.format(Locale.US, "%,.2f", v);
    }
}
