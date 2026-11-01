package dev.mobzone;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.Locale;

/** Small shared helpers. */
final class Util {
    static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private Util() {}

    static Component c(String legacy) { return LEGACY.deserialize(legacy); }

    static void tell(CommandSender to, String legacy) { to.sendMessage(c(legacy)); }

    static void broadcast(String legacy) { Bukkit.broadcast(c(legacy)); }

    static void console(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    static String money(long n) { return String.format(Locale.US, "%,d", n); }

    static double num(Object o, double def) { return o instanceof Number n ? n.doubleValue() : def; }

    static String str(Object o, String def) { return o == null ? def : String.valueOf(o); }
}
