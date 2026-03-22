package com.lopikss.lsenderchest.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

public final class InventorySerializer {

    private InventorySerializer() {
    }

    public static String serialize(ItemStack[] items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }

            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception exception) {
            throw new RuntimeException("Failed to serialize inventory.", exception);
        }
    }

    public static ItemStack[] deserialize(String data) {
        if (data == null || data.isBlank()) {
            return new ItemStack[54];
        }

        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);

            int length = dataInput.readInt();
            ItemStack[] items = new ItemStack[Math.max(length, 54)];

            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }

            dataInput.close();

            if (items.length != 54) {
                ItemStack[] fixed = new ItemStack[54];
                System.arraycopy(items, 0, fixed, 0, Math.min(items.length, 54));
                return fixed;
            }

            return items;
        } catch (Exception exception) {
            throw new RuntimeException("Failed to deserialize inventory.", exception);
        }
    }
}