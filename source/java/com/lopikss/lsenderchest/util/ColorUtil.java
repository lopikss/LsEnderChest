package com.lopikss.lsenderchest.util;

import org.bukkit.ChatColor;

public final class ColorUtil {

    private ColorUtil() {
    }

    public static String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }
}