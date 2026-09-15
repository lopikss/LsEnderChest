package com.lopikss.lsenderchest.overflow;

import java.util.UUID;

public record OverflowRecord(
        UUID id,
        UUID ownerUuid,
        long createdAt,
        int sourceRows,
        int targetRows,
        String contents
) {
}
