package com.example.tradingplatform.auth;

import java.time.Instant;
import java.util.Set;

public record User(
        String id,
        String username,
        Set<String> roles,
        Instant createdAt
) {
}
