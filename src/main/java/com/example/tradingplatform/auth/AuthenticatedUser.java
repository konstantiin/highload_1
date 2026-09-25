package com.example.tradingplatform.auth;

import java.util.Set;

public record AuthenticatedUser(String id, String username, Set<String> roles, KycStatus kycStatus) {
    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
