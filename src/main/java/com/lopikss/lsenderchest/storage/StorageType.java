package com.lopikss.lsenderchest.storage;

import java.util.Locale;

public enum StorageType {
    SQLITE,
    MYSQL;

    public static StorageType fromString(String value) {
        if (value == null || value.isBlank()) {
            return SQLITE;
        }

        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported storage.type '" + value + "'. Use 'sqlite' or 'mysql'.");
        }
    }
}
