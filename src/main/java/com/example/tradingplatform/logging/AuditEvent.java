package com.example.tradingplatform.logging;

import java.time.Instant;
import java.util.Set;

public record AuditEvent(
        String id,
        Instant occurredAt,
        String type,
        Set<String> tags,
        String actorUserId,
        String message
) {
}
