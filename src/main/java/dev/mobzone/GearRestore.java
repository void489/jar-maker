package dev.mobzone;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gives a player their sword and armor back at the levels saved in LuckPerms meta (ench_*, arm_*).
 * Replaces any existing Prison Blade / armor, never touches the upgrade levels, ignores kit limits.
 */
final class GearRestore {
    private static final String SWORD = "iron_sword[custom_name={text:\"Prison Blade\",color:\"red\",italic:false},"
            + "lore=[{text:\"Type /enchants to upgrade\",color:\"gray\",italic:false}],unbreakable={}]";
    private static final String[][] PIECES = {
            {"head", "leather_helmet", "Prison Helmet"}, {"chest", "leather_chestplate", "Prison Chestplate"},
            {"legs", "leather_leggings", "Prison Leggings"}, {"feet", "leather_boots", "Prison Boots"}};
    private static final String[] SWORD_ENCH = {"sharpness", "double_strike", "vampire", "temper", "venom", "ice_aspect", "thunder", "rage"};

    private GearRestore() {}

    private static int level(Player p, String key) {
        String v = RankBonus.placeholder(p, "%luckperms_meta_" + key + "%");
        if (v == null) return 0;
        try { return Math.max(0, Integer.parseInt(v.trim())); } catch (NumberFormatException e) { return 0; }
    }

    private static String ns(String id) { return id.equals("sharpness") ? "minecraft:sharpness" : "excellentenchants:" + id; }

    private static boolean isBlade(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta() || it.getItemMeta().displayName() == null) return false;
        return PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName()).toLowerCase(Locale.ROOT).contains("prison blade");
    }

    /** what = sword | armor | all. Returns lines to show the admin. */
    static List<String> run(Player p, String what) {
        List<String> out = new ArrayList<>();
        String n = p.getName();
        boolean sword = what.equals("all") || what.equals("sword");
        boolean armor = what.equals("all") || what.equals("armor");
        if (sword) {
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) if (isBlade(inv.getItem(i))) inv.setItem(i, null);
            if (isBlade(inv.getItemInOffHand())) inv.setItemInOffHand(null);
            int slot = -1;
            for (int i = 0; i < 9; i++) if (inv.getItem(i) == null || inv.getItem(i).getType().isAir()) { slot = i; break; }
            if (slot < 0) {
                ItemStack moved = inv.getItem(0);
                java.util.Map<Integer, ItemStack> left = inv.addItem(moved.clone());
                if (!left.isEmpty()) { out.add("&c" + n + "'s inventory is full. Free a slot and run it again."); return out; }
                inv.setItem(0, null);
                // addItem may have put the clone back into slot 0; make sure slot 0 is empty
                slot = 0;
            }
            Util.console("minecraft:item replace entity " + n + " hotbar." + slot + " with " + SWORD);
            inv.setHeldItemSlot(slot);
            int applied = 0;
            for (String e : SWORD_ENCH) {
                int lv = level(p, "ench_" + e);
                if (lv > 0) { Util.console("eenchants enchant " + ns(e) + " " + lv + " " + n + " hand"); applied++; }
            }
            out.add("&aSword restored with " + applied + " saved enchant(s).");
        }
        if (armor) {
            for (String[] pc : PIECES)
                Util.console("minecraft:item replace entity " + n + " armor." + pc[0] + " with " + pc[1]
                        + "[custom_name={text:\"" + pc[2] + "\",color:\"green\",italic:false},lore=[{text:\"Type /armor to upgrade\",color:\"gray\",italic:false}],"
                        + "unbreakable={},enchantments={binding_curse:1}]");
            int prot = level(p, "arm_prot"), blast = level(p, "arm_blast"), thorns = level(p, "arm_thorns"),
                    hard = level(p, "arm_hardened"), fire = level(p, "arm_fire_shield");
            for (String[] pc : PIECES) {
                if (prot > 0) Util.console("eenchants enchant minecraft:protection " + prot + " " + n + " " + pc[0]);
                if (blast > 0) Util.console("eenchants enchant minecraft:blast_protection " + blast + " " + n + " " + pc[0]);
            }
            if (thorns > 0) Util.console("eenchants enchant minecraft:thorns " + thorns + " " + n + " chest");
            if (hard > 0) Util.console("eenchants enchant excellentenchants:hardened " + hard + " " + n + " chest");
            if (fire > 0) Util.console("eenchants enchant excellentenchants:fire_shield " + fire + " " + n + " chest");
            out.add("&aArmor restored (prot " + prot + ", blast " + blast + ", thorns " + thorns + ", hardened " + hard + ", fire shield " + fire + ").");
            out.add("&7Dragon Heart and Speed are on the player, not the armor, so they were not touched.");
        }
        return out;
    }
}
