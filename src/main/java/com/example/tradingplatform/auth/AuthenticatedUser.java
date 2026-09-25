package com.example.tradingplatform.auth;

import java.util.Set;

public record AuthenticatedUser(String id, String username, Set<String> roles) {
    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
