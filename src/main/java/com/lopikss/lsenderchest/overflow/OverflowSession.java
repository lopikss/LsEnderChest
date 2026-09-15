package com.lopikss.lsenderchest.overflow;

import com.lopikss.lsenderchest.util.ItemStackUtil;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class OverflowSession {

    private final OverflowRecord originalRecord;
    private final UUID sessionId = UUID.randomUUID();
    private final List<ItemStack> contents = new ArrayList<>();
    private UUID viewerUuid;

    OverflowSession(OverflowRecord record, ItemStack[] decoded) {
        this.originalRecord = record;
        if (decoded != null) {
            for (ItemStack item : decoded) {
                ItemStack normalized = ItemStackUtil.normalize(item);
                if (normalized != null) {
                    contents.add(normalized);
                }
            }
        }
    }

    OverflowRecord originalRecord() {
        return originalRecord;
    }

    UUID sessionId() {
        return sessionId;
    }

    int pageCount() {
        return Math.max(1, (contents.size() + 44) / 45);
    }

    List<ItemStack> pageItems(int page) {
        int start = Math.max(0, page) * 45;
        List<ItemStack> result = new ArrayList<>(45);
        for (int i = 0; i < 45; i++) {
            int index = start + i;
            result.add(index < contents.size() ? ItemStackUtil.normalize(contents.get(index)) : null);
        }
        return result;
    }

    void updatePage(int page, ItemStack[] pageContents) {
        int start = Math.max(0, page) * 45;
        while (contents.size() < start + 45) {
            contents.add(null);
        }
        for (int i = 0; i < 45; i++) {
            ItemStack item = pageContents != null && i < pageContents.length
                    ? ItemStackUtil.normalize(pageContents[i])
                    : null;
            contents.set(start + i, item);
        }
        compactTrailingEmpty();
    }

    ItemStack[] snapshot() {
        List<ItemStack> compact = new ArrayList<>();
        for (ItemStack item : contents) {
            ItemStack normalized = ItemStackUtil.normalize(item);
            if (normalized != null) {
                compact.add(normalized);
            }
        }
        return ItemStackUtil.toArray(compact);
    }

    boolean claimViewer(UUID uuid) {
        if (viewerUuid != null && !viewerUuid.equals(uuid)) {
            return false;
        }
        viewerUuid = uuid;
        return true;
    }

    boolean isViewer(UUID uuid) {
        return uuid != null && uuid.equals(viewerUuid);
    }

    boolean hasViewer() {
        return viewerUuid != null;
    }

    void releaseViewer(UUID uuid) {
        if (isViewer(uuid)) {
            viewerUuid = null;
        }
    }

    private void compactTrailingEmpty() {
        int last = contents.size() - 1;
        while (last >= 0 && ItemStackUtil.normalize(contents.get(last)) == null) {
            contents.remove(last--);
        }
    }
}
