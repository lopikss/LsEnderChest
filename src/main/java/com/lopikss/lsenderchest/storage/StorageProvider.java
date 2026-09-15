package com.lopikss.lsenderchest.storage;

import com.lopikss.lsenderchest.overflow.OverflowRecord;

import java.util.UUID;

public interface StorageProvider extends AutoCloseable {

    void init() throws Exception;

    String load(UUID playerUuid) throws Exception;

    void save(UUID playerUuid, String playerName, String contents) throws Exception;

    OverflowRecord loadOverflow(UUID overflowId) throws Exception;

    void saveOverflow(OverflowRecord record) throws Exception;

    boolean deleteOverflow(UUID overflowId) throws Exception;

    @Override
    void close() throws Exception;
}
