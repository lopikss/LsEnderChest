package com.lopikss.lsenderchest.util;

import org.bukkit.inventory.ItemStack;

public final class ItemStackUtil {

    private ItemStackUtil() {
    }

    public static ItemStack[] cloneArray(ItemStack[] source, int targetSize) {
        ItemStack[] result = new ItemStack[targetSize];
        if (source == null) {
            return result;
        }

        for (int i = 0; i < Math.min(source.length, targetSize); i++) {
            result[i] = source[i] == null ? null : source[i].clone();
        }
        return result;
    }
}