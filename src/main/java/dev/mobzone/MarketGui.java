package dev.mobzone;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Menus and commands for the per-mode economy: /sell, /worth, /orders, /order, /ah.
 * These only work in game modes switched on in economy.modes (default: oneblock). Commands are caught before they
 * reach Prison/Essentials, so Prison's own /sell keeps working in the prison.
 */
public final class MarketGui implements Listener {
    private static final int ITEMS = 45, LAST = 53;
    private static final String[] ORDER_SORTS = {"Newest first", "Highest price", "Lowest price", "Most needed"};
    private static final String[] AH_SORTS = {"Newest first", "Lowest price", "Highest price"};

    // ---------------------------------------------------------------- holders

    private abstract static class H implements InventoryHolder {
        Inventory inv; String mode; boolean done;
        @Override public Inventory getInventory() { return inv; }
    }
    private abstract static class DepositH extends H { abstract boolean accepts(ItemStack it); }
    private final class SellH extends DepositH {
        @Override boolean accepts(ItemStack it) { return Market.plain(it); }
    }
    private final class DeliverH extends DepositH {
        long orderId;
        @Override boolean accepts(ItemStack it) {
            Market.Order o = market.order(orderId);
            return o != null && Market.plain(it) && it.getType() == o.item;
        }
    }
    private static final class BoardH extends H { String query; int page, sort; List<Market.Order> shown = new ArrayList<>(); }
    private static final class MineH extends H { List<Market.Order> shown = new ArrayList<>(); }
    private static final class AhH extends H { String query; int page, sort; List<Market.Listing> shown = new ArrayList<>(); }
    private static final class MyAhH extends H { List<Market.Listing> shown = new ArrayList<>(); }
    private static final class BuyH extends H { long listingId; }
    private static final class ShopMenuH extends H { List<Market.ShopCat> cats = new ArrayList<>(); }
    private static final class ShopH extends H { Market.ShopCat cat; int page; List<Map.Entry<Material, Long>> items = new ArrayList<>(); }

    private static final class Prompt { int step; Material item; int amount; String mode; }

    private final MobZonePlugin plugin;
    private final Market market;
    private final Map<UUID, Prompt> prompts = new HashMap<>();

    public MarketGui(MobZonePlugin plugin, Market market) { this.plugin = plugin; this.market = market; }

    // ---------------------------------------------------------------- item helpers

    private static Component line(String s) { return Util.c(s).decoration(TextDecoration.ITALIC, false); }

    private static ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(line(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(line(s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack withLore(ItemStack base, List<String> add) {
        ItemStack it = base.clone();
        ItemMeta meta = it.getItemMeta();
        List<Component> l = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        for (String s : add) l.add(line(s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private static String left(long expires) {
        long ms = expires - System.currentTimeMillis();
        if (ms <= 0) return "expired";
        long d = ms / 86_400_000L, h = (ms % 86_400_000L) / 3_600_000L, m = (ms % 3_600_000L) / 60_000L;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return Math.max(1, m) + "m";
    }

    private static void give(Player p, ItemStack it) {
        for (ItemStack rest : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
    }

    private static boolean hasSpace(Player p, ItemStack it) {
        if (p.getInventory().firstEmpty() != -1) return true;
        int room = 0;
        for (ItemStack s : p.getInventory().getStorageContents())
            if (s != null && s.isSimilar(it)) room += s.getMaxStackSize() - s.getAmount();
        return room >= it.getAmount();
    }

    private Inventory open(Player p, H h, int size, String title) {
        h.inv = Bukkit.createInventory(h, size, Util.c(title));
        return h.inv;
    }

    private void later(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    // ---------------------------------------------------------------- commands

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String raw = e.getMessage().trim();
        if (raw.startsWith("/")) raw = raw.substring(1);
        String[] parts = raw.split("\\s+");
        if (parts.length == 0) return;
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if (!(cmd.equals("sell") || cmd.equals("worth") || cmd.equals("shop") || cmd.equals("orders") || cmd.equals("order") || cmd.equals("ah") || cmd.equals("auctionhouse"))) return;
        Player p = e.getPlayer();
        String mode = market.modeFor(p);
        if (mode == null) {
            if (cmd.equals("sell") || cmd.equals("worth") || cmd.equals("shop")) return;      // leave Prison's /sell and other plugins' /worth, /shop alone
            e.setCancelled(true);
            Util.tell(p, "&cThat only works in Oneblock.");
            return;
        }
        e.setCancelled(true);
        String[] a = new String[parts.length - 1];
        System.arraycopy(parts, 1, a, 0, a.length);
        switch (cmd) {
            case "sell" -> openSell(p, mode);
            case "shop" -> openShopMenu(p, mode);
            case "worth" -> worthCmd(p, mode, a);
            case "orders" -> openBoard(p, mode, null);
            case "order" -> orderCmd(p, mode, a);
            default -> ahCmd(p, mode, a);
        }
    }

    private static String join(String[] a, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < a.length; i++) { if (sb.length() > 0) sb.append(' '); sb.append(a[i]); }
        return sb.toString();
    }

    private static long parseNum(String s) {
        try { return Long.parseLong(s.replace(",", "").replace("$", "")); } catch (NumberFormatException ex) { return -1; }
    }

    private void worthCmd(Player p, String mode, String[] a) {
        Material m;
        if (a.length == 0) {
            ItemStack h = p.getInventory().getItemInMainHand();
            if (h.getType().isAir()) { Util.tell(p, "&7Hold an item or use &f/worth <item>&7."); return; }
            m = h.getType();
        } else {
            m = market.findMaterial(join(a, 0));
            if (m == null) { Util.tell(p, "&cCouldn't find that item."); return; }
        }
        double w = market.worthOf(mode, m);
        long best = market.bestOrder(mode, m, p.getUniqueId());
        if (w <= 0 && best <= 0) { Util.tell(p, "&7" + Market.nice(m) + " &7can't be sold to the server and has no orders."); return; }
        Util.tell(p, "&#A3E635[Worth] &f" + Market.nice(m) + "&7: &a$" + Market.fmt(Math.max(w, best)) + " &7each"
                + (best > w ? " &8(best order; server worth $" + Market.fmt(w) + ")" : (best > 0 ? " &8(best order $" + Market.fmt(best) + ")" : "")));
    }

    private void orderCmd(Player p, String mode, String[] a) {
        if (a.length == 0) { openBoard(p, mode, null); return; }
        if (a[0].equalsIgnoreCase("mine")) { openMine(p, mode); return; }
        if (a[0].equalsIgnoreCase("create")) {
            if (a.length < 4) { Util.tell(p, "&7Use &f/order create <item> <amount> <price each>&7, or make one from &f/orders &7> My Orders."); return; }
            Material m = market.findMaterial(join(java.util.Arrays.copyOfRange(a, 1, a.length - 2), 0));
            long amt = parseNum(a[a.length - 2]), price = parseNum(a[a.length - 1]);
            if (m == null) { Util.tell(p, "&cCouldn't find that item."); return; }
            placeOrder(p, mode, m, (int) Math.min(amt, Integer.MAX_VALUE), price);
            return;
        }
        openBoard(p, mode, join(a, 0));
    }

    private void placeOrder(Player p, String mode, Material m, int amount, long price) {
        String err = market.createOrder(p, mode, m, amount, price);
        if (err != null) { Util.tell(p, "&#A3E635[Orders] &c" + err); return; }
        Util.tell(p, "&#A3E635[Orders] &7Order placed: &f" + amount + "x " + Market.nice(m) + " &7at &a$" + Util.money(price)
                + " &7each. &a$" + Util.money(amount * price) + " &7is held until it fills or you cancel.");
    }

    // ---------------------------------------------------------------- sell menu

    private void openSell(Player p, String mode) {
        SellH h = new SellH(); h.mode = mode;
        open(p, h, 54, "&8Sell items");
        refreshDeposit(p, h);
        p.openInventory(h.inv);
    }

    private Map<Material, Integer> countPlain(Inventory inv) {
        Map<Material, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < ITEMS; i++) {
            ItemStack it = inv.getItem(i);
            if (Market.plain(it)) m.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        return m;
    }

    private int countAll(Inventory inv) {
        int n = 0;
        for (int i = 0; i < ITEMS; i++) { ItemStack it = inv.getItem(i); if (it != null && !it.getType().isAir()) n += it.getAmount(); }
        return n;
    }

    private void refreshDeposit(Player p, DepositH d) {
        if (d instanceof DeliverH dh) {
            Market.Order o = market.order(dh.orderId);
            if (o == null || !o.active(System.currentTimeMillis())) {
                d.inv.setItem(LAST, icon(Material.RED_STAINED_GLASS_PANE, "&cThis order is closed", "&7Close the menu to get your items back."));
                return;
            }
            int cnt = countPlain(d.inv).getOrDefault(o.item, 0);
            int qty = Math.min(cnt, o.remaining());
            List<String> l = new ArrayList<>();
            l.add("&7Items going in: &f" + qty + "x " + Market.nice(o.item));
            l.add("&7You get: &a$" + Util.money(qty * o.price) + " &7(&a$" + Util.money(o.price) + " &7each)");
            l.add("&7Order still needs: &f" + o.remaining());
            if (cnt > qty) l.add("&7" + (cnt - qty) + " extra will be returned");
            l.add(qty > 0 ? "&eClick to confirm" : "&7Put " + Market.nice(o.item) + " in the menu");
            d.inv.setItem(LAST, icon(Material.LIME_STAINED_GLASS_PANE, "&a&lConfirm delivery", l.toArray(new String[0])));
            return;
        }
        Market.Plan plan = market.plan(d.mode, p.getUniqueId(), countPlain(d.inv));
        int all = countAll(d.inv);
        List<String> l = new ArrayList<>();
        l.add("&7Total value: &a$" + Util.money(plan.total()));
        l.add("&7Items sold: &f" + plan.items());
        if (plan.orderItems > 0) l.add("&7To orders (pay more than worth): &f" + plan.orderItems + " &7for &a$" + Util.money(plan.orderValue));
        if (plan.worthItems > 0) l.add("&7At worth: &f" + plan.worthItems + " &7for &a$" + Util.money(Math.round(plan.worthValue)));
        if (all > plan.items()) l.add("&cNot sellable: " + (all - plan.items()) + " (returned to you)");
        l.add(plan.items() > 0 ? "&eClick to confirm and sell" : "&7Put items in the menu above");
        d.inv.setItem(LAST, icon(Material.LIME_STAINED_GLASS_PANE, "&a&lConfirm sale", l.toArray(new String[0])));
    }

    /** Removes up to qty plain items of a material from the deposit slots. */
    private void removePlain(Inventory inv, Material m, int qty) {
        for (int i = 0; i < ITEMS && qty > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (!Market.plain(it) || it.getType() != m) continue;
            int take = Math.min(qty, it.getAmount());
            qty -= take;
            if (take >= it.getAmount()) inv.setItem(i, null); else it.setAmount(it.getAmount() - take);
        }
    }

    private void returnItems(Player p, Inventory inv) {
        for (int i = 0; i < ITEMS; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            inv.setItem(i, null);
            give(p, it);
        }
    }

    private void confirm(Player p, DepositH d) {
        if (d instanceof DeliverH dh) { confirmDeliver(p, dh); return; }
        Map<Material, Integer> counts = countPlain(d.inv);
        Market.Plan plan = market.plan(d.mode, p.getUniqueId(), counts);
        if (plan.items() == 0) { Util.tell(p, "&#A3E635[Sell] &cNothing in there can be sold."); return; }
        for (var e : plan.sold.entrySet()) removePlain(d.inv, e.getKey(), e.getValue());
        long total = market.execute(p, plan);
        returnItems(p, d.inv);
        d.done = true;
        p.closeInventory();
        Util.tell(p, "&#A3E635[Sell] &7Sold &f" + plan.items() + " &7items for &a$" + Util.money(total)
                + (plan.orderItems > 0 ? " &8(" + plan.orderItems + " went to orders for $" + Util.money(plan.orderValue) + ")" : ""));
    }

    private void confirmDeliver(Player p, DeliverH d) {
        Market.Order o = market.order(d.orderId);
        if (o == null || !o.active(System.currentTimeMillis())) { Util.tell(p, "&#A3E635[Orders] &cThat order is closed."); return; }
        if (o.owner.equals(p.getUniqueId())) { Util.tell(p, "&#A3E635[Orders] &cYou can't deliver to your own order."); return; }
        int qty = Math.min(countPlain(d.inv).getOrDefault(o.item, 0), o.remaining());
        if (qty <= 0) { Util.tell(p, "&#A3E635[Orders] &cPut " + Market.nice(o.item) + " in the menu first."); return; }
        removePlain(d.inv, o.item, qty);
        Market.Plan pl = new Market.Plan();
        Market.Alloc a = new Market.Alloc(); a.order = o; a.qty = qty;
        pl.allocs.add(a); pl.orderItems = qty; pl.orderValue = qty * o.price;
        long paid = market.execute(p, pl);
        returnItems(p, d.inv);
        d.done = true;
        p.closeInventory();
        Util.tell(p, "&#A3E635[Orders] &7Delivered &f" + qty + "x " + Market.nice(o.item) + " &7and got &a$" + Util.money(paid) + "&7.");
    }

    // ---------------------------------------------------------------- shop (buy-only)

    private static final int[] CAT_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    private void openShopMenu(Player p, String mode) {
        ShopMenuH h = new ShopMenuH(); h.mode = mode;
        h.cats = market.shopOf(mode);
        open(p, h, 27, "&8Shop");
        for (int i = 0; i < h.cats.size() && i < CAT_SLOTS.length; i++) {
            Market.ShopCat c = h.cats.get(i);
            h.inv.setItem(CAT_SLOTS[i], icon(c.icon, c.name, "&7" + c.items.size() + " items", "&eClick to open"));
        }
        if (h.cats.isEmpty()) h.inv.setItem(13, icon(Material.BARRIER, "&cThe shop is empty", "&7Add items in shop.yml"));
        p.openInventory(h.inv);
    }

    private void openShop(Player p, String mode, Market.ShopCat cat, int page) {
        ShopH h = new ShopH(); h.mode = mode; h.cat = cat; h.page = page;
        h.items = new ArrayList<>(cat.items.entrySet());
        int pages = Math.max(1, (h.items.size() + ITEMS - 1) / ITEMS);
        h.page = Math.max(0, Math.min(h.page, pages - 1));
        open(p, h, 54, "&8Shop: " + cat.name.replaceAll("&[0-9a-fk-or]", "") + (pages > 1 ? " (" + (h.page + 1) + "/" + pages + ")" : ""));
        for (int i = 0; i < ITEMS; i++) {
            int idx = h.page * ITEMS + i;
            if (idx >= h.items.size()) break;
            var en = h.items.get(idx);
            List<String> l = new ArrayList<>();
            l.add("&7Price: &a$" + Util.money(en.getValue()));
            l.add(en.getKey().getMaxStackSize() > 1
                    ? "&eLeft-click: buy 1  &8|  &eRight: buy 16  &8|  &eShift: buy 64"
                    : "&eClick to buy");
            h.inv.setItem(i, withLore(new ItemStack(en.getKey()), l));
        }
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = ITEMS; i <= LAST; i++) h.inv.setItem(i, pane);
        if (h.page > 0) h.inv.setItem(45, icon(Material.ARROW, "&ePrevious page", "&7Page " + h.page + "/" + pages));
        h.inv.setItem(48, icon(Material.BARRIER, "&cBack to categories"));
        long bal = WalletManager.readBalance(p);
        h.inv.setItem(49, icon(Material.GOLD_INGOT, "&6Your cash", bal == Long.MIN_VALUE ? "&7Unknown" : "&a$" + Util.money(bal), "&7Sell things with &f/sell"));
        if (h.page < pages - 1) h.inv.setItem(LAST, icon(Material.ARROW, "&eNext page", "&7Page " + (h.page + 2) + "/" + pages));
        p.openInventory(h.inv);
    }

    private void shopBuy(Player p, ShopH h, Material m, long price, ClickType click) {
        if (!h.mode.equals(market.modeFor(p))) { p.closeInventory(); return; }
        int max = m.getMaxStackSize();
        int qty = max <= 1 ? 1 : switch (click) {
            case RIGHT, SHIFT_RIGHT -> click == ClickType.RIGHT ? 16 : 64;
            case SHIFT_LEFT -> 64;
            default -> 1;
        };
        qty = Math.min(qty, max);
        int cap = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) cap += max;
            else if (s.getType() == m && Market.plain(s)) cap += Math.max(0, max - s.getAmount());
        }
        if (cap <= 0) { Util.tell(p, "&#A3E635[Shop] &cYour inventory is full."); return; }
        qty = Math.min(qty, cap);
        long total = price * qty;
        long bal = WalletManager.readBalance(p);
        if (bal == Long.MIN_VALUE) { Util.tell(p, "&#A3E635[Shop] &cCouldn't read your balance right now."); return; }
        if (bal < total) { Util.tell(p, "&#A3E635[Shop] &cYou need $" + Util.money(total) + " (you have $" + Util.money(bal) + ")."); return; }
        Util.console("eco take " + p.getName() + " " + total);
        give(p, new ItemStack(m, qty));
        Util.tell(p, "&#A3E635[Shop] &7Bought &f" + qty + "x " + Market.nice(m) + " &7for &a$" + Util.money(total) + "&7.");
        h.inv.setItem(49, icon(Material.GOLD_INGOT, "&6Your cash", "&a$" + Util.money(bal - total), "&7Sell things with &f/sell"));
    }

    // ---------------------------------------------------------------- order board

    private void openBoard(Player p, String mode, String query) {
        BoardH h = new BoardH(); h.mode = mode; h.query = query == null || query.isBlank() ? null : query;
        open(p, h, 54, h.query == null ? "&8Orders" : "&8Orders: " + h.query);
        buildBoard(p, h);
        p.openInventory(h.inv);
    }

    private void buildBoard(Player p, BoardH h) {
        h.shown = market.board(h.mode, h.query, h.sort);
        int pages = Math.max(1, (h.shown.size() + ITEMS - 1) / ITEMS);
        h.page = Math.max(0, Math.min(h.page, pages - 1));
        h.inv.clear();
        long now = System.currentTimeMillis();
        for (int i = 0; i < ITEMS; i++) {
            int idx = h.page * ITEMS + i;
            if (idx >= h.shown.size()) break;
            Market.Order o = h.shown.get(idx);
            double eff = Math.max(market.worthOf(h.mode, o.item), o.price);
            List<String> l = new ArrayList<>();
            l.add("&7Price each: &a$" + Util.money(o.price));
            l.add("&7Needed: &f" + o.remaining() + "&7/&f" + o.amount);
            l.add("&7Ordered by: &f" + o.ownerName);
            l.add("&7Worth: &a$" + Market.fmt(eff) + " &7each");
            l.add("&7Expires in: &f" + left(o.expires));
            l.add(o.owner.equals(p.getUniqueId()) ? "&8Your order (manage it in My Orders)" : "&eClick to deliver items");
            h.inv.setItem(i, withLore(new ItemStack(o.item, Math.max(1, Math.min(o.remaining(), o.item.getMaxStackSize()))), l));
        }
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = ITEMS; i <= LAST; i++) h.inv.setItem(i, pane);
        if (h.page > 0) h.inv.setItem(45, icon(Material.ARROW, "&ePrevious page", "&7Page " + h.page + "/" + pages));
        List<String> sl = new ArrayList<>();
        for (int i = 0; i < ORDER_SORTS.length; i++) sl.add((i == h.sort ? "&a> " : "&7  ") + ORDER_SORTS[i]);
        sl.add("&8Click to change");
        h.inv.setItem(46, icon(Material.HOPPER, "&eSort", sl.toArray(new String[0])));
        h.inv.setItem(48, icon(Material.PAPER, "&eSearch", "&7Use &f/order <item>", "&7Example: &f/order elytra",
                h.query == null ? "&7Showing: &fall orders" : "&7Showing: &f" + h.query));
        h.inv.setItem(49, icon(Material.CHEST, "&6My Orders", "&7Make an order, collect items,", "&7or cancel for a refund."));
        h.inv.setItem(50, icon(Material.BOOK, "&eHow selling works", "&7/sell auto-sends items to an order", "&7when it pays more than the worth.",
                "&7Or click an order here to deliver."));
        if (h.page < pages - 1) h.inv.setItem(LAST, icon(Material.ARROW, "&eNext page", "&7Page " + (h.page + 2) + "/" + pages));
        if (h.shown.isEmpty()) h.inv.setItem(22, icon(Material.BARRIER, "&cNo orders found", "&7Make one in &fMy Orders&7."));
    }

    private void openDeliver(Player p, String mode, Market.Order o) {
        DeliverH h = new DeliverH(); h.mode = mode; h.orderId = o.id;
        open(p, h, 54, "&8Deliver: " + Market.nice(o.item));
        refreshDeposit(p, h);
        p.openInventory(h.inv);
    }

    // ---------------------------------------------------------------- my orders

    private void openMine(Player p, String mode) {
        MineH h = new MineH(); h.mode = mode;
        open(p, h, 54, "&8My Orders");
        buildMine(p, h);
        p.openInventory(h.inv);
    }

    private void buildMine(Player p, MineH h) {
        h.shown = market.mine(h.mode, p.getUniqueId());
        h.inv.clear();
        for (int i = 0; i < ITEMS && i < h.shown.size(); i++) {
            Market.Order o = h.shown.get(i);
            String status = o.filled >= o.amount ? "&aComplete" : o.closed ? "&cClosed" : System.currentTimeMillis() >= o.expires ? "&cExpired" : "&eOpen";
            List<String> l = new ArrayList<>();
            l.add("&7Status: " + status);
            l.add("&7Price each: &a$" + Util.money(o.price));
            l.add("&7Filled: &f" + o.filled + "&7/&f" + o.amount);
            if (o.stored > 0) l.add("&7Items waiting: &f" + o.stored);
            if (o.refund > 0) l.add("&7Refund waiting: &a$" + Util.money(o.refund));
            if (!o.done()) l.add("&7Expires in: &f" + left(o.expires) + " &7(held: &a$" + Util.money(o.remaining() * o.price) + "&7)");
            l.add("&eLeft-click: collect");
            if (!o.done()) l.add("&cRight-click: cancel and refund");
            h.inv.setItem(i, withLore(new ItemStack(o.item, Math.max(1, Math.min(Math.max(o.stored, o.remaining()), o.item.getMaxStackSize()))), l));
        }
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = ITEMS; i <= LAST; i++) h.inv.setItem(i, pane);
        h.inv.setItem(45, icon(Material.ARROW, "&eBack to orders"));
        h.inv.setItem(49, icon(Material.EMERALD, "&a&l+ New order", "&7Pick an item, amount and price each.", "&7The cash is held until it fills.",
                "&7Orders: &f" + h.shown.size() + "&7/&f" + market.cfgInt("max-orders", 10)));
    }

    private void startPrompt(Player p, String mode) {
        Prompt pr = new Prompt(); pr.mode = mode; pr.step = 1;
        prompts.put(p.getUniqueId(), pr);
        p.closeInventory();
        Util.tell(p, "&#A3E635[Orders] &7Type the &fitem name &7in chat (like &felytra&7), or &fhand &7for the item you hold. Type &fcancel &7to stop.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!prompts.containsKey(id)) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        later(() -> prompt(p, text));
    }

    private void prompt(Player p, String text) {
        Prompt pr = prompts.get(p.getUniqueId());
        if (pr == null) return;
        if (text.equalsIgnoreCase("cancel")) { prompts.remove(p.getUniqueId()); Util.tell(p, "&#A3E635[Orders] &7Cancelled."); return; }
        if (pr.step == 1) {
            Material m = text.equalsIgnoreCase("hand") ? p.getInventory().getItemInMainHand().getType() : market.findMaterial(text);
            if (m == null || m.isAir()) { Util.tell(p, "&#A3E635[Orders] &cCouldn't find that item. Try again, or type &fcancel&c."); return; }
            pr.item = m; pr.step = 2;
            Util.tell(p, "&#A3E635[Orders] &7Item: &f" + Market.nice(m) + "&7. How many do you want? (1-" + Util.money(market.cfgInt("max-order-amount", 2304)) + ")");
        } else if (pr.step == 2) {
            long n = parseNum(text);
            if (n < 1 || n > market.cfgInt("max-order-amount", 2304)) { Util.tell(p, "&#A3E635[Orders] &cType a whole number in range, or &fcancel&c."); return; }
            pr.amount = (int) n; pr.step = 3;
            Util.tell(p, "&#A3E635[Orders] &7How much &fper item&7? Worth is &a$" + Market.fmt(market.worthOf(pr.mode, pr.item)) + "&7. Higher prices get filled first.");
        } else {
            long price = parseNum(text);
            if (price < 1) { Util.tell(p, "&#A3E635[Orders] &cType a whole number, or &fcancel&c."); return; }
            prompts.remove(p.getUniqueId());
            placeOrder(p, pr.mode, pr.item, pr.amount, price);
            openMine(p, pr.mode);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { prompts.remove(e.getPlayer().getUniqueId()); }

    // ---------------------------------------------------------------- auction house

    private void ahCmd(Player p, String mode, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("sell")) { ahSell(p, mode, a); return; }
        if (a.length > 0 && a[0].equalsIgnoreCase("mine")) { openMyAh(p, mode); return; }
        openAh(p, mode, a.length == 0 ? null : join(a, 0));
    }

    private void ahSell(Player p, String mode, String[] a) {
        if (a.length < 2) { Util.tell(p, "&#A3E635[AH] &7Use &f/ah sell <price>&7. The price is for everything in your hand."); return; }
        long price = parseNum(a[1]);
        if (price < 1 || price > market.cfgInt("ah-max-price", 1_000_000_000)) { Util.tell(p, "&#A3E635[AH] &cType a whole price, like &f/ah sell 500&c."); return; }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { Util.tell(p, "&#A3E635[AH] &cHold the item you want to sell."); return; }
        int amount = hand.getAmount();
        double ask = (double) price / amount;                       // price per item
        int left = amount;
        Market.Plan pl = new Market.Plan();
        if (Market.plain(hand)) {
            long now = System.currentTimeMillis();
            List<Market.Order> cands = new ArrayList<>();
            for (Market.Order o : market.board(mode, hand.getType().name(), 1))
                if (o.item == hand.getType() && !o.owner.equals(p.getUniqueId()) && o.price >= ask && o.active(now)) cands.add(o);
            for (Market.Order o : cands) {                           // board(sort 1) = highest price first
                if (left <= 0) break;
                int take = Math.min(left, o.remaining());
                Market.Alloc al = new Market.Alloc(); al.order = o; al.qty = take;
                pl.allocs.add(al); pl.orderItems += take; pl.orderValue += take * o.price;
                left -= take;
            }
        }
        long listPrice = pl.orderItems == 0 ? price : Math.max(1, Math.round(price * (double) left / amount));
        if (left > 0 && market.myListings(mode, p.getUniqueId()).size() >= market.cfgInt("ah-max-listings", 10)) {
            Util.tell(p, "&#A3E635[AH] &cYou already have " + market.cfgInt("ah-max-listings", 10) + " listings. Take some back with &f/ah mine&c.");
            return;
        }
        if (pl.orderItems > 0) {
            long paid = market.execute(p, pl);
            Util.tell(p, "&#A3E635[AH] &7Sold &f" + pl.orderItems + "x " + Market.nice(hand.getType()) + " &7straight to orders for &a$" + Util.money(paid) + "&7.");
        }
        if (left > 0) {
            ItemStack rest = hand.clone(); rest.setAmount(left);
            market.addListing(p, mode, rest, listPrice);
            Util.tell(p, "&#A3E635[AH] &7Listed &f" + left + "x " + Market.nice(hand.getType()) + " &7for &a$" + Util.money(listPrice)
                    + " &7for " + market.cfgInt("ah-days", 3) + " days.");
        }
        p.getInventory().setItemInMainHand(null);       // the whole stack was either sold to orders or listed
    }

    private void collectPayout(Player p, String mode) {
        long v = market.takePayout(mode, p.getUniqueId());
        if (v <= 0) return;
        Util.console("eco give " + p.getName() + " " + v);
        Util.tell(p, "&#A3E635[AH] &7You earned &a$" + Util.money(v) + " &7from sold items.");
    }

    private void openAh(Player p, String mode, String query) {
        collectPayout(p, mode);
        AhH h = new AhH(); h.mode = mode; h.query = query == null || query.isBlank() ? null : query;
        open(p, h, 54, h.query == null ? "&8Auction House" : "&8Auction: " + h.query);
        buildAh(p, h);
        p.openInventory(h.inv);
    }

    private void buildAh(Player p, AhH h) {
        h.shown = market.shop(h.mode, h.query, h.sort);
        int pages = Math.max(1, (h.shown.size() + ITEMS - 1) / ITEMS);
        h.page = Math.max(0, Math.min(h.page, pages - 1));
        h.inv.clear();
        for (int i = 0; i < ITEMS; i++) {
            int idx = h.page * ITEMS + i;
            if (idx >= h.shown.size()) break;
            Market.Listing l = h.shown.get(idx);
            List<String> add = new ArrayList<>();
            add.add("&7Price: &a$" + Util.money(l.price));
            add.add("&7Seller: &f" + l.sellerName);
            if (Market.plain(l.item)) {
                double eff = market.effective(h.mode, l.item.getType(), null);
                if (eff > 0) add.add("&7Worth: &a$" + Market.fmt(eff) + " &7each");
            }
            add.add("&7Expires in: &f" + left(l.expires));
            add.add(l.seller.equals(p.getUniqueId()) ? "&8Your listing" : "&eClick to buy");
            h.inv.setItem(i, withLore(l.item, add));
        }
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = ITEMS; i <= LAST; i++) h.inv.setItem(i, pane);
        if (h.page > 0) h.inv.setItem(45, icon(Material.ARROW, "&ePrevious page", "&7Page " + h.page + "/" + pages));
        List<String> sl = new ArrayList<>();
        for (int i = 0; i < AH_SORTS.length; i++) sl.add((i == h.sort ? "&a> " : "&7  ") + AH_SORTS[i]);
        sl.add("&8Click to change");
        h.inv.setItem(46, icon(Material.HOPPER, "&eSort", sl.toArray(new String[0])));
        h.inv.setItem(48, icon(Material.PAPER, "&eSearch and sell", "&7Search: &f/ah <item>", "&7Sell what you hold: &f/ah sell <price>",
                "&7The price is for the whole stack."));
        h.inv.setItem(49, icon(Material.CHEST, "&6Your listings", "&7Take items back or cancel."));
        if (h.page < pages - 1) h.inv.setItem(LAST, icon(Material.ARROW, "&eNext page", "&7Page " + (h.page + 2) + "/" + pages));
        if (h.shown.isEmpty()) h.inv.setItem(22, icon(Material.BARRIER, "&cNothing for sale", "&7Sell with &f/ah sell <price>&7."));
    }

    private void openMyAh(Player p, String mode) {
        MyAhH h = new MyAhH(); h.mode = mode;
        open(p, h, 54, "&8Your listings");
        buildMyAh(p, h);
        p.openInventory(h.inv);
    }

    private void buildMyAh(Player p, MyAhH h) {
        h.shown = market.myListings(h.mode, p.getUniqueId());
        h.inv.clear();
        long now = System.currentTimeMillis();
        for (int i = 0; i < ITEMS && i < h.shown.size(); i++) {
            Market.Listing l = h.shown.get(i);
            List<String> add = new ArrayList<>();
            add.add("&7Price: &a$" + Util.money(l.price));
            if (l.active(now)) { add.add("&7Expires in: &f" + left(l.expires)); add.add("&cClick to cancel and take it back"); }
            else add.add("&eExpired. Click to take it back");
            h.inv.setItem(i, withLore(l.item, add));
        }
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = ITEMS; i <= LAST; i++) h.inv.setItem(i, pane);
        h.inv.setItem(45, icon(Material.ARROW, "&eBack to auction house"));
    }

    private void openBuy(Player p, String mode, Market.Listing l) {
        BuyH h = new BuyH(); h.mode = mode; h.listingId = l.id;
        open(p, h, 27, "&8Confirm purchase");
        h.inv.setItem(13, withLore(l.item, List.of("&7Price: &a$" + Util.money(l.price), "&7Seller: &f" + l.sellerName)));
        h.inv.setItem(11, icon(Material.LIME_STAINED_GLASS_PANE, "&a&lBuy for $" + Util.money(l.price), "&7Click to confirm"));
        h.inv.setItem(15, icon(Material.RED_STAINED_GLASS_PANE, "&c&lCancel"));
        p.openInventory(h.inv);
    }

    private void buy(Player p, BuyH h) {
        Market.Listing l = market.listing(h.listingId);
        if (l == null || !l.active(System.currentTimeMillis())) { Util.tell(p, "&#A3E635[AH] &cThat listing is gone."); openAh(p, h.mode, null); return; }
        if (l.seller.equals(p.getUniqueId())) { Util.tell(p, "&#A3E635[AH] &cThat's your own listing."); return; }
        long bal = WalletManager.readBalance(p);
        if (bal == Long.MIN_VALUE) { Util.tell(p, "&#A3E635[AH] &cCouldn't read your balance right now."); return; }
        if (bal < l.price) { Util.tell(p, "&#A3E635[AH] &cYou need $" + Util.money(l.price) + " (you have $" + Util.money(bal) + ")."); return; }
        if (!hasSpace(p, l.item)) { Util.tell(p, "&#A3E635[AH] &cYour inventory is full."); return; }
        Util.console("eco take " + p.getName() + " " + l.price);
        give(p, l.item.clone());
        market.removeListing(l);
        int tax = Math.max(0, Math.min(100, market.cfgInt("ah-tax", 0)));
        long net = l.price - l.price * tax / 100;
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null && h.mode.equals(Market.modeOf(seller.getWorld().getName()))) {
            Util.console("eco give " + seller.getName() + " " + net);
            Util.tell(seller, "&#A3E635[AH] &f" + p.getName() + " &7bought your &f" + Market.nice(l.item.getType()) + " &7for &a$" + Util.money(net) + "&7.");
        } else {
            market.addPayout(h.mode, l.seller, net);
            if (seller != null) Util.tell(seller, "&#A3E635[AH] &7Your item sold! Open &f/ah &7in Oneblock to collect &a$" + Util.money(net) + "&7.");
        }
        h.done = true;
        p.closeInventory();
        Util.tell(p, "&#A3E635[AH] &7You bought &f" + l.item.getAmount() + "x " + Market.nice(l.item.getType()) + " &7for &a$" + Util.money(l.price) + "&7.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { remind(e.getPlayer(), 80L); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { remind(e.getPlayer(), 40L); }

    private void remind(Player p, long delay) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            String mode = market.modeFor(p);
            if (mode == null) return;
            long v = market.payoutOf(mode, p.getUniqueId());
            if (v > 0) Util.tell(p, "&#A3E635[AH] &7You have &a$" + Util.money(v) + " &7waiting from sold items. Open &f/ah &7to collect.");
        }, delay);
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof H h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        boolean top = e.getClickedInventory() != null && e.getClickedInventory().equals(e.getView().getTopInventory());
        if (h instanceof DepositH d) { depositClick(e, p, d, top); return; }
        e.setCancelled(true);
        if (!top) return;
        int slot = e.getRawSlot();
        boolean right = e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT;
        if (h instanceof BoardH b) boardClick(p, b, slot, right);
        else if (h instanceof MineH m) mineClick(p, m, slot, right);
        else if (h instanceof AhH a) ahClick(p, a, slot, right);
        else if (h instanceof MyAhH m) myAhClick(p, m, slot);
        else if (h instanceof ShopMenuH sm) {
            for (int i = 0; i < CAT_SLOTS.length && i < sm.cats.size(); i++) if (CAT_SLOTS[i] == slot) openShop(p, sm.mode, sm.cats.get(i), 0);
        }
        else if (h instanceof ShopH sh) {
            if (slot < ITEMS) {
                int idx = sh.page * ITEMS + slot;
                if (idx < sh.items.size()) shopBuy(p, sh, sh.items.get(idx).getKey(), sh.items.get(idx).getValue(), e.getClick());
            } else if (slot == 45 && sh.page > 0) openShop(p, sh.mode, sh.cat, sh.page - 1);
            else if (slot == LAST) openShop(p, sh.mode, sh.cat, sh.page + 1);
            else if (slot == 48) openShopMenu(p, sh.mode);
        }
        else if (h instanceof BuyH b) {
            if (slot == 11) buy(p, b);
            else if (slot == 15) openAh(p, b.mode, null);
        }
    }

    private void boardClick(Player p, BoardH h, int slot, boolean right) {
        if (slot < ITEMS) {
            int idx = h.page * ITEMS + slot;
            if (idx >= h.shown.size()) return;
            Market.Order o = h.shown.get(idx);
            if (!o.active(System.currentTimeMillis())) { Util.tell(p, "&#A3E635[Orders] &cThat order is closed."); buildBoard(p, h); return; }
            if (o.owner.equals(p.getUniqueId())) { openMine(p, h.mode); return; }
            openDeliver(p, h.mode, o);
            return;
        }
        if (slot == 45 && h.page > 0) { h.page--; buildBoard(p, h); }
        else if (slot == LAST) { h.page++; buildBoard(p, h); }
        else if (slot == 46) { h.sort = (h.sort + (right ? ORDER_SORTS.length - 1 : 1)) % ORDER_SORTS.length; h.page = 0; buildBoard(p, h); }
        else if (slot == 49) openMine(p, h.mode);
    }

    private void mineClick(Player p, MineH h, int slot, boolean right) {
        if (slot < ITEMS) {
            if (slot >= h.shown.size()) return;
            Market.Order o = h.shown.get(slot);
            if (right && !o.done()) market.cancel(p, o); else market.collect(p, o);
            buildMine(p, h);
            return;
        }
        if (slot == 45) openBoard(p, h.mode, null);
        else if (slot == 49) startPrompt(p, h.mode);
    }

    private void ahClick(Player p, AhH h, int slot, boolean right) {
        if (slot < ITEMS) {
            int idx = h.page * ITEMS + slot;
            if (idx >= h.shown.size()) return;
            Market.Listing l = h.shown.get(idx);
            if (!l.active(System.currentTimeMillis())) { Util.tell(p, "&#A3E635[AH] &cThat listing is gone."); buildAh(p, h); return; }
            if (l.seller.equals(p.getUniqueId())) { openMyAh(p, h.mode); return; }
            openBuy(p, h.mode, l);
            return;
        }
        if (slot == 45 && h.page > 0) { h.page--; buildAh(p, h); }
        else if (slot == LAST) { h.page++; buildAh(p, h); }
        else if (slot == 46) { h.sort = (h.sort + (right ? AH_SORTS.length - 1 : 1)) % AH_SORTS.length; h.page = 0; buildAh(p, h); }
        else if (slot == 49) openMyAh(p, h.mode);
    }

    private void myAhClick(Player p, MyAhH h, int slot) {
        if (slot == 45) { openAh(p, h.mode, null); return; }
        if (slot >= ITEMS || slot >= h.shown.size()) return;
        Market.Listing l = h.shown.get(slot);
        if (market.listing(l.id) == null) { buildMyAh(p, h); return; }
        if (!hasSpace(p, l.item)) { Util.tell(p, "&#A3E635[AH] &cYour inventory is full."); return; }
        give(p, l.item.clone());
        market.removeListing(l);
        Util.tell(p, "&#A3E635[AH] &7Took back &f" + l.item.getAmount() + "x " + Market.nice(l.item.getType()) + "&7.");
        buildMyAh(p, h);
    }

    // ---------------------------------------------------------------- deposit menus (sell + deliver)

    private void depositClick(InventoryClickEvent e, Player p, DepositH d, boolean top) {
        int raw = e.getRawSlot();
        if (top && raw == LAST) { e.setCancelled(true); confirm(p, d); return; }
        if (top && raw >= ITEMS) { e.setCancelled(true); return; }                 // the bottom row stays empty
        ClickType ct = e.getClick();
        if (ct == ClickType.NUMBER_KEY || ct == ClickType.SWAP_OFFHAND || ct == ClickType.DOUBLE_CLICK || ct == ClickType.CREATIVE) {
            e.setCancelled(true);
            return;
        }
        if (e.isShiftClick()) {
            if (top) { later(() -> refreshDeposit(p, d)); return; }                  // top -> your inventory: normal
            e.setCancelled(true);
            ItemStack cur = e.getCurrentItem();
            if (cur == null || cur.getType().isAir()) return;
            if (!d.accepts(cur)) { Util.tell(p, d instanceof DeliverH ? "&cOnly the ordered item fits here." : "&cOnly plain items can be sold."); return; }
            moveIn(d.inv, cur);
            e.setCurrentItem(cur.getAmount() <= 0 ? null : cur);
            later(() -> refreshDeposit(p, d));
            return;
        }
        if (top) {
            ItemStack cursor = e.getCursor();
            if (cursor != null && !cursor.getType().isAir() && !d.accepts(cursor)) {
                e.setCancelled(true);
                Util.tell(p, d instanceof DeliverH ? "&cOnly the ordered item fits here." : "&cOnly plain items can be sold.");
                return;
            }
        }
        later(() -> refreshDeposit(p, d));
    }

    /** Shift-click: puts an item into the first free deposit slots (never the bottom row). */
    private void moveIn(Inventory inv, ItemStack src) {
        for (int i = 0; i < ITEMS && src.getAmount() > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !it.isSimilar(src) || it.getAmount() >= it.getMaxStackSize()) continue;
            int add = Math.min(it.getMaxStackSize() - it.getAmount(), src.getAmount());
            it.setAmount(it.getAmount() + add);
            src.setAmount(src.getAmount() - add);
        }
        for (int i = 0; i < ITEMS && src.getAmount() > 0; i++) {
            if (inv.getItem(i) != null) continue;
            ItemStack put = src.clone();
            put.setAmount(Math.min(src.getMaxStackSize(), src.getAmount()));
            inv.setItem(i, put);
            src.setAmount(src.getAmount() - put.getAmount());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof H h)) return;
        int size = e.getView().getTopInventory().getSize();
        boolean touchesTop = false;
        for (int raw : e.getRawSlots()) {
            if (raw >= size) continue;
            touchesTop = true;
            if (!(h instanceof DepositH) || raw >= ITEMS) { e.setCancelled(true); return; }
        }
        if (touchesTop && h instanceof DepositH d) {
            if (!d.accepts(e.getOldCursor())) { e.setCancelled(true); return; }
            if (e.getWhoClicked() instanceof Player p) later(() -> refreshDeposit(p, d));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof DepositH d && !d.done && e.getPlayer() instanceof Player p) {
            d.done = true;
            returnItems(p, d.inv);
        }
    }

    /** On shutdown/reload: give back anything left in open sell/deliver menus. */
    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof DepositH d && !d.done) {
                d.done = true;
                returnItems(p, d.inv);
                p.closeInventory();
            } else if (p.getOpenInventory().getTopInventory().getHolder() instanceof H) p.closeInventory();
        }
    }
}
