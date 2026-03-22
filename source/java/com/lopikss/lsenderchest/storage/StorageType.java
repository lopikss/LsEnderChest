package com.lopikss.lsenderchest.storage;

public enum StorageType {
    SQLITE,
    MYSQL;

    public static StorageType fromString(String input) {
        if (input == null) {
            return SQLITE;
        }

        return switch (input.toLowerCase()) {
            case "mysql" -> MYSQL;
            default -> SQLITE;
        };
    }
}