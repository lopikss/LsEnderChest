package com.lopikss.lsenderchest.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class ItemStackUtil {

    private ItemStackUtil() {
    }

    public static ItemStack[] cloneArray(ItemStack[] source, int targetSize) {
        ItemStack[] result = new ItemStack[Math.max(0, targetSize)];
        if (source == null) {
            return result;
        }

        for (int i = 0; i < Math.min(source.length, result.length); i++) {
            result[i] = normalize(source[i]);
        }
        return result;
    }

    public static List<ItemStack> cloneNonEmpty(ItemStack[] source, int fromInclusive, int toExclusive) {
        List<ItemStack> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        int start = Math.max(0, fromInclusive);
        int end = Math.min(source.length, Math.max(start, toExclusive));
        for (int i = start; i < end; i++) {
            ItemStack item = normalize(source[i]);
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    public static ItemStack[] toArray(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return new ItemStack[0];
        }
        ItemStack[] result = new ItemStack[items.size()];
        for (int i = 0; i < items.size(); i++) {
            result[i] = normalize(items.get(i));
        }
        return result;
    }

    public static boolean isEmpty(ItemStack[] items) {
        if (items == null) {
            return true;
        }
        for (ItemStack item : items) {
            if (normalize(item) != null) {
                return false;
            }
        }
        return true;
    }

    public static int highestOccupiedSlot(ItemStack[] items) {
        if (items == null) {
            return -1;
        }
        for (int i = items.length - 1; i >= 0; i--) {
            if (normalize(items[i]) != null) {
                return i;
            }
        }
        return -1;
    }

    public static boolean contentsEqual(ItemStack[] left, ItemStack[] right, int size) {
        for (int i = 0; i < size; i++) {
            ItemStack a = left != null && i < left.length ? normalize(left[i]) : null;
            ItemStack b = right != null && i < right.length ? normalize(right[i]) : null;
            if (a == null ? b != null : !a.equals(b)) {
                return false;
            }
        }
        return true;
    }

    public static boolean contentsEqual(ItemStack[] left, ItemStack[] right) {
        int size = Math.max(left == null ? 0 : left.length, right == null ? 0 : right.length);
        return contentsEqual(left, right, size);
    }

    public static int countNonEmpty(ItemStack[] items) {
        int count = 0;
        if (items != null) {
            for (ItemStack item : items) {
                if (normalize(item) != null) {
                    count++;
                }
            }
        }
        return count;
    }

    public static ItemStack normalize(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getType().isAir() || item.getAmount() <= 0) {
            return null;
        }
        return item.clone();
    }
}
