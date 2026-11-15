package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Per-mode economy data: sell worth, the order book and the auction house.
 * Everything is tagged with a mode ("oneblock", later "smp") so modes never share cash, prices, orders or listings.
 * Files: worth.yml (hardcoded prices, editable), orders.yml, ah.yml.
 */
public final class Market {
    // ---------------------------------------------------------------- models

    static final class Order {
        long id; String mode; UUID owner; String ownerName; Material item;
        int amount, filled, stored; long price, created, expires, refund; boolean closed;
        int remaining() { return amount - filled; }
        boolean done() { return closed || filled >= amount; }
        boolean active(long now) { return !done() && now < expires; }
    }

    static final class Listing {
        long id; String mode; UUID seller; String sellerName; ItemStack item;
        long price, created, expires; boolean ended;
        boolean active(long now) { return !ended && now < expires; }
    }

    static final class Alloc { Order order; int qty; }

    static final class Plan {
        final Map<Material, Integer> sold = new LinkedHashMap<>();   // total quantity actually sold per material
        final List<Alloc> allocs = new ArrayList<>();                // quantities going to orders
        double worthValue; long orderValue; int orderItems, worthItems;
        long total() { return Math.round(worthValue) + orderValue; }
        int items() { return orderItems + worthItems; }
    }

    // ---------------------------------------------------------------- state

    private final MobZonePlugin plugin;
    private final Map<String, Map<Material, Double>> worth = new HashMap<>();
    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private final Map<Long, Listing> listings = new LinkedHashMap<>();
    private final Map<String, Map<UUID, Long>> payouts = new HashMap<>();
    private long nextId = 1;
    private boolean dirty;
    private int taskId = -1;

    public Market(MobZonePlugin plugin) { this.plugin = plugin; }

    // ---------------------------------------------------------------- modes + config

    static String modeOf(String world) {
        String w = world.toLowerCase(Locale.ROOT);
        if (w.startsWith("oneblock")) return "oneblock";
        if (w.startsWith("smp")) return "smp";
        return null;
    }

    /** The mode this player is in, only if the economy commands are switched on for it. */
    String modeFor(Player p) {
        String m = modeOf(p.getWorld().getName());
        if (m == null) return null;
        List<String> on = plugin.getConfig().getStringList("economy.modes");
        if (on.isEmpty()) on = List.of("oneblock");
        for (String s : on) if (s.equalsIgnoreCase(m)) return m;
        return null;
    }

    int cfgInt(String path, int def) { return plugin.getConfig().getInt("economy." + path, def); }

    // ---------------------------------------------------------------- load / save

    public void reload() {
        stop();
        worth.clear(); orders.clear(); listings.clear(); payouts.clear();
        File wf = new File(plugin.getDataFolder(), "worth.yml");
        if (!wf.exists()) plugin.saveResource("worth.yml", false);
        var wy = YamlConfiguration.loadConfiguration(wf);
        int bad = 0;
        for (String mode : wy.getKeys(false)) {
            var sec = wy.getConfigurationSection(mode);
            if (sec == null) continue;
            Map<Material, Double> m = new HashMap<>();
            for (String k : sec.getKeys(false)) {
                Material mat = Material.matchMaterial(k);
                if (mat == null || mat.isAir() || !mat.isItem()) { bad++; continue; }
                m.put(mat, sec.getDouble(k));
            }
            worth.put(mode.toLowerCase(Locale.ROOT), m);
        }
        if (bad > 0) plugin.getLogger().warning("worth.yml: skipped " + bad + " unknown item name(s)");

        var oy = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "orders.yml"));
        nextId = Math.max(1, oy.getLong("next-id", 1));
        var os = oy.getConfigurationSection("orders");
        if (os != null) for (String k : os.getKeys(false)) {
            var s = os.getConfigurationSection(k);
            if (s == null) continue;
            try {
                Order o = new Order();
                o.id = Long.parseLong(k);
                o.mode = s.getString("mode", "oneblock");
                o.owner = UUID.fromString(s.getString("owner", ""));
                o.ownerName = s.getString("owner-name", "?");
                o.item = Material.matchMaterial(s.getString("item", ""));
                if (o.item == null) continue;
                o.amount = s.getInt("amount"); o.filled = s.getInt("filled"); o.stored = s.getInt("stored");
                o.price = s.getLong("price"); o.created = s.getLong("created"); o.expires = s.getLong("expires");
                o.refund = s.getLong("refund"); o.closed = s.getBoolean("closed");
                orders.put(o.id, o);
                nextId = Math.max(nextId, o.id + 1);
            } catch (RuntimeException ignored) { }
        }

        var ay = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "ah.yml"));
        nextId = Math.max(nextId, ay.getLong("next-id", 1));
        var ls = ay.getConfigurationSection("listings");
        if (ls != null) for (String k : ls.getKeys(false)) {
            var s = ls.getConfigurationSection(k);
            if (s == null) continue;
            try {
                Listing l = new Listing();
                l.id = Long.parseLong(k);
                l.mode = s.getString("mode", "oneblock");
                l.seller = UUID.fromString(s.getString("seller", ""));
                l.sellerName = s.getString("seller-name", "?");
                l.item = ItemStack.deserializeBytes(Base64.getDecoder().decode(s.getString("item", "")));
                l.price = s.getLong("price"); l.created = s.getLong("created"); l.expires = s.getLong("expires");
                l.ended = s.getBoolean("ended");
                listings.put(l.id, l);
                nextId = Math.max(nextId, l.id + 1);
            } catch (RuntimeException ignored) { }
        }
        var ps = ay.getConfigurationSection("payouts");
        if (ps != null) for (String mode : ps.getKeys(false)) {
            var s = ps.getConfigurationSection(mode);
            if (s == null) continue;
            Map<UUID, Long> m = new HashMap<>();
            for (String u : s.getKeys(false)) try { m.put(UUID.fromString(u), s.getLong(u)); } catch (IllegalArgumentException ignored) { }
            payouts.put(mode, m);
        }
        start();
    }

    private void start() {
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> { expire(); if (dirty) save(); }, 200L, 200L).getTaskId();
    }

    private void stop() {
        if (taskId != -1) { Bukkit.getScheduler().cancelTask(taskId); taskId = -1; }
    }

    public void shutdown() { stop(); if (dirty) save(); }

    void touch() { dirty = true; }

    private void save() {
        dirty = false;
        var oy = new YamlConfiguration();
        oy.set("next-id", nextId);
        for (Order o : orders.values()) {
            String p = "orders." + o.id + ".";
            oy.set(p + "mode", o.mode); oy.set(p + "owner", o.owner.toString()); oy.set(p + "owner-name", o.ownerName);
            oy.set(p + "item", o.item.name()); oy.set(p + "amount", o.amount); oy.set(p + "filled", o.filled);
            oy.set(p + "stored", o.stored); oy.set(p + "price", o.price); oy.set(p + "created", o.created);
            oy.set(p + "expires", o.expires); oy.set(p + "refund", o.refund); oy.set(p + "closed", o.closed);
        }
        var ay = new YamlConfiguration();
        ay.set("next-id", nextId);
        for (Listing l : listings.values()) {
            String p = "listings." + l.id + ".";
            ay.set(p + "mode", l.mode); ay.set(p + "seller", l.seller.toString()); ay.set(p + "seller-name", l.sellerName);
            ay.set(p + "item", Base64.getEncoder().encodeToString(l.item.serializeAsBytes()));
            ay.set(p + "price", l.price); ay.set(p + "created", l.created); ay.set(p + "expires", l.expires);
            ay.set(p + "ended", l.ended);
        }
        for (var e : payouts.entrySet()) for (var b : e.getValue().entrySet())
            if (b.getValue() > 0) ay.set("payouts." + e.getKey() + "." + b.getKey(), b.getValue());
        try {
            oy.save(new File(plugin.getDataFolder(), "orders.yml"));
            ay.save(new File(plugin.getDataFolder(), "ah.yml"));
        } catch (IOException ex) { plugin.getLogger().warning("Couldn't save orders/ah: " + ex.getMessage()); }
    }

    /** Closes orders and listings that ran out of time. Money and items stay held until the owner collects. */
    void expire() {
        long now = System.currentTimeMillis();
        for (Order o : orders.values())
            if (!o.closed && o.filled < o.amount && now >= o.expires) { o.closed = true; o.refund += o.remaining() * o.price; dirty = true; }
        for (Listing l : listings.values())
            if (!l.ended && now >= l.expires) { l.ended = true; dirty = true; }
    }

    // ---------------------------------------------------------------- worth

    double worthOf(String mode, Material m) { return worth.getOrDefault(mode, Map.of()).getOrDefault(m, 0.0); }

    /** Best open order price for an item, or 0. Skips the given player's own orders. */
    long bestOrder(String mode, Material m, UUID except) {
        long now = System.currentTimeMillis(), best = 0;
        for (Order o : orders.values())
            if (o.mode.equals(mode) && o.item == m && o.active(now) && (except == null || !o.owner.equals(except)) && o.price > best) best = o.price;
        return best;
    }

    /** What an item is worth to this player: the hardcoded worth, or the best order if that pays more. */
    double effective(String mode, Material m, UUID viewer) { return Math.max(worthOf(mode, m), bestOrder(mode, m, viewer)); }

    static String fmt(double v) {
        if (v == Math.rint(v)) return Util.money((long) v);
        return String.format(Locale.US, "%,.2f", v);
    }

    // ---------------------------------------------------------------- sell plan

    /** Works out where every item would go: the best order if it pays more than the worth, otherwise the worth. */
    Plan plan(String mode, UUID seller, Map<Material, Integer> counts) {
        Plan plan = new Plan();
        long now = System.currentTimeMillis();
        for (var e : counts.entrySet()) {
            Material mat = e.getKey();
            int left = e.getValue();
            double w = worthOf(mode, mat);
            List<Order> cands = new ArrayList<>();
            for (Order o : orders.values())
                if (o.mode.equals(mode) && o.item == mat && o.active(now) && !o.owner.equals(seller) && o.price > w && o.remaining() > 0) cands.add(o);
            cands.sort(Comparator.comparingLong((Order o) -> -o.price).thenComparingLong(o -> o.created));
            for (Order o : cands) {
                if (left <= 0) break;
                int take = Math.min(left, o.remaining());
                Alloc a = new Alloc(); a.order = o; a.qty = take;
                plan.allocs.add(a);
                plan.orderValue += take * o.price;
                plan.orderItems += take;
                plan.sold.merge(mat, take, Integer::sum);
                left -= take;
            }
            if (left > 0 && w > 0) {
                plan.worthValue += left * w;
                plan.worthItems += left;
                plan.sold.merge(mat, left, Integer::sum);
            }
        }
        return plan;
    }

    /** Applies a plan: fills the orders and pays the seller. The caller removes the sold items first. Returns the payout. */
    long execute(Player p, Plan plan) {
        for (Alloc a : plan.allocs) {
            Order o = a.order;
            o.filled += a.qty; o.stored += a.qty;
            Player owner = Bukkit.getPlayer(o.owner);
            if (owner != null) Util.tell(owner, "&#A3E635[Orders] &f" + p.getName() + " &7delivered &f" + a.qty + "x " + nice(o.item)
                    + " &7to your order" + (o.filled >= o.amount ? " &a(complete)" : " &7(" + o.filled + "/" + o.amount + ")") + "&7. Collect it in &f/orders &7> My Orders.");
        }
        long total = plan.total();
        if (total > 0) Util.console("eco give " + p.getName() + " " + total);
        dirty = true;
        return total;
    }

    // ---------------------------------------------------------------- orders

    static String nice(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String s : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1));
        }
        return sb.toString();
    }

    /** Finds a material from what a player typed: exact name first, then names that contain it. */
    Material findMaterial(String q) {
        String k = q.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (k.isEmpty()) return null;
        Material exact = Material.matchMaterial(k);
        if (exact != null && exact.isItem() && !exact.isAir()) return exact;
        Material best = null;
        for (Material m : Material.values()) {
            if (!m.isItem() || m.isAir() || m.name().startsWith("LEGACY_")) continue;
            String n = m.name().toLowerCase(Locale.ROOT);
            if (n.contains(k) && (best == null || n.length() < best.name().length())) best = m;
        }
        return best;
    }

    List<Order> board(String mode, String query, int sort) {
        long now = System.currentTimeMillis();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        List<Order> out = new ArrayList<>();
        for (Order o : orders.values()) {
            if (!o.mode.equals(mode) || !o.active(now)) continue;
            if (!q.isEmpty() && !o.item.name().toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(o);
        }
        switch (sort) {
            case 1 -> out.sort(Comparator.comparingLong((Order o) -> -o.price).thenComparingLong(o -> -o.created));
            case 2 -> out.sort(Comparator.comparingLong((Order o) -> o.price).thenComparingLong(o -> -o.created));
            case 3 -> out.sort(Comparator.comparingInt((Order o) -> -o.remaining()).thenComparingLong(o -> -o.created));
            default -> out.sort(Comparator.comparingLong((Order o) -> -o.created));
        }
        return out;
    }

    List<Order> mine(String mode, UUID owner) {
        List<Order> out = new ArrayList<>();
        for (Order o : orders.values()) if (o.mode.equals(mode) && o.owner.equals(owner)) out.add(o);
        out.sort(Comparator.comparingLong((Order o) -> -o.created));
        return out;
    }

    Order order(long id) { return orders.get(id); }

    /** Creates an order and holds the cash. Returns an error message, or null on success. */
    String createOrder(Player p, String mode, Material item, int amount, long price) {
        int maxAmt = cfgInt("max-order-amount", 2304), maxPrice = cfgInt("max-price", 1_000_000), maxOrders = cfgInt("max-orders", 10);
        if (item == null || item.isAir() || !item.isItem()) return "That isn't an item.";
        if (amount < 1 || amount > maxAmt) return "Amount must be 1 to " + Util.money(maxAmt) + ".";
        if (price < 1 || price > maxPrice) return "Price each must be 1 to " + Util.money(maxPrice) + ".";
        int have = 0;
        for (Order o : mine(mode, p.getUniqueId())) have++;
        if (have >= maxOrders) return "You already have " + maxOrders + " orders. Collect or cancel some first.";
        long cost = amount * price;
        long bal = WalletManager.readBalance(p);
        if (bal == Long.MIN_VALUE) return "Couldn't read your balance right now.";
        if (bal < cost) return "You need $" + Util.money(cost) + " to hold for this order (you have $" + Util.money(bal) + ").";
        Util.console("eco take " + p.getName() + " " + cost);
        Order o = new Order();
        o.id = nextId++; o.mode = mode; o.owner = p.getUniqueId(); o.ownerName = p.getName(); o.item = item;
        o.amount = amount; o.price = price; o.created = System.currentTimeMillis();
        o.expires = o.created + cfgInt("order-days", 7) * 86_400_000L;
        orders.put(o.id, o);
        dirty = true;
        return null;
    }

    /** Hands the owner their delivered items (as many as fit) and any refund. Removes the order when nothing is left. */
    void collect(Player p, Order o) {
        int gave = 0;
        while (o.stored > 0) {
            int n = Math.min(o.stored, o.item.getMaxStackSize());
            var left = p.getInventory().addItem(new ItemStack(o.item, n));
            int back = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            gave += n - back;
            o.stored -= n - back;
            if (back > 0) break;
        }
        if (gave > 0) Util.tell(p, "&#A3E635[Orders] &7Collected &f" + gave + "x " + nice(o.item) + "&7.");
        if (o.stored > 0) Util.tell(p, "&#A3E635[Orders] &cYour inventory is full. &7" + o.stored + " more are waiting for you.");
        if (o.refund > 0) {
            Util.console("eco give " + p.getName() + " " + o.refund);
            Util.tell(p, "&#A3E635[Orders] &7Refunded &a$" + Util.money(o.refund) + "&7.");
            o.refund = 0;
        }
        if (o.done() && o.stored == 0 && o.refund == 0) orders.remove(o.id);
        dirty = true;
    }

    /** Cancels an open order: the cash for the unfilled part comes back when collected. */
    void cancel(Player p, Order o) {
        if (!o.done()) { o.closed = true; o.refund += o.remaining() * o.price; }
        collect(p, o);
    }

    // ---------------------------------------------------------------- auction house

    List<Listing> shop(String mode, String query, int sort) {
        long now = System.currentTimeMillis();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        List<Listing> out = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (!l.mode.equals(mode) || !l.active(now)) continue;
            if (!q.isEmpty() && !l.item.getType().name().toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(l);
        }
        switch (sort) {
            case 1 -> out.sort(Comparator.comparingLong((Listing l) -> l.price));
            case 2 -> out.sort(Comparator.comparingLong((Listing l) -> -l.price));
            default -> out.sort(Comparator.comparingLong((Listing l) -> -l.created));
        }
        return out;
    }

    List<Listing> myListings(String mode, UUID seller) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : listings.values()) if (l.mode.equals(mode) && l.seller.equals(seller)) out.add(l);
        out.sort(Comparator.comparingLong((Listing l) -> -l.created));
        return out;
    }

    Listing listing(long id) { return listings.get(id); }

    Listing addListing(Player p, String mode, ItemStack item, long price) {
        Listing l = new Listing();
        l.id = nextId++; l.mode = mode; l.seller = p.getUniqueId(); l.sellerName = p.getName();
        l.item = item.clone(); l.price = price; l.created = System.currentTimeMillis();
        l.expires = l.created + cfgInt("ah-days", 3) * 86_400_000L;
        listings.put(l.id, l);
        dirty = true;
        return l;
    }

    void removeListing(Listing l) { listings.remove(l.id); dirty = true; }

    long takePayout(String mode, UUID id) {
        var m = payouts.get(mode);
        if (m == null) return 0;
        Long v = m.remove(id);
        if (v != null) dirty = true;
        return v == null ? 0 : v;
    }

    long payoutOf(String mode, UUID id) { return payouts.getOrDefault(mode, Map.of()).getOrDefault(id, 0L); }

    void addPayout(String mode, UUID id, long amount) {
        payouts.computeIfAbsent(mode, k -> new HashMap<>()).merge(id, amount, Long::sum);
        dirty = true;
    }

    /** Only plain items (no name, enchants, damage, lore) take part in worth, selling and orders. */
    static boolean plain(ItemStack it) { return it != null && !it.getType().isAir() && !it.hasItemMeta(); }
}
