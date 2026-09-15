package com.lopikss.lsenderchest.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.util.Base64;

public final class InventoryCodec {

    private static final int CHEST_SIZE = 54;
    private static final String PAPER_NBT_PREFIX = "paper-nbt-v1:";

    private InventoryCodec() {
    }

    /** Serializes a normal LsEnderChest as exactly 54 slots. */
    public static String serialize(ItemStack[] items) {
        return serializeExact(ItemStackUtil.cloneArray(items, CHEST_SIZE));
    }

    /** Serializes an arbitrary sized ItemStack array using Paper's data-versioned item format. */
    public static String serializeExact(ItemStack[] items) {
        ItemStack[] normalized = ItemStackUtil.cloneArray(items, items == null ? 0 : items.length);
        byte[] bytes = ItemStack.serializeItemsAsBytes(normalized);
        return PAPER_NBT_PREFIX + Base64.getEncoder().encodeToString(bytes);
    }

    /** Deserializes a normal LsEnderChest and always returns exactly 54 slots. */
    public static ItemStack[] deserialize(String data) {
        return ItemStackUtil.cloneArray(deserializeExact(data), CHEST_SIZE);
    }

    /** Deserializes the stored array at its original size. Legacy data remains supported. */
    public static ItemStack[] deserializeExact(String data) {
        if (data == null || data.isBlank()) {
            return new ItemStack[0];
        }

        try {
            if (data.startsWith(PAPER_NBT_PREFIX)) {
                String encoded = data.substring(PAPER_NBT_PREFIX.length());
                ItemStack[] items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded));
                return ItemStackUtil.cloneArray(items, items.length);
            }

            return deserializeLegacy(data);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to deserialize inventory contents.", exception);
        }
    }

    private static ItemStack[] deserializeLegacy(String data) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(data);
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            int length = input.readInt();
            if (length < 0 || length > 216) {
                throw new IllegalArgumentException("Invalid legacy inventory size: " + length);
            }

            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                Object value = input.readObject();
                if (value != null && !(value instanceof ItemStack)) {
                    throw new IllegalArgumentException("Legacy inventory contains a non-ItemStack value.");
                }
                items[i] = (ItemStack) value;
            }
            return ItemStackUtil.cloneArray(items, items.length);
        }
    }
}
