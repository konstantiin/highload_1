package com.example.tradingplatform.logging;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class AuditLogService {
    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    public AuditEvent write(String type, Set<String> tags, String actorUserId, String message) {
        AuditEvent event = new AuditEvent(UUID.randomUUID().toString(), Instant.now(), type, Set.copyOf(tags), actorUserId, message);
        events.add(event);
        return event;
    }

    public List<AuditEvent> query(Instant from, Instant to, Set<String> requiredTags) {
        return events.stream()
                .filter(event -> from == null || !event.occurredAt().isBefore(from))
                .filter(event -> to == null || !event.occurredAt().isAfter(to))
                .filter(event -> requiredTags == null || requiredTags.isEmpty() || event.tags().containsAll(requiredTags))
                .sorted(Comparator.comparing(AuditEvent::occurredAt))
                .toList();
    }

    public List<AuditEvent> all() {
        return new ArrayList<>(events);
    }
}
