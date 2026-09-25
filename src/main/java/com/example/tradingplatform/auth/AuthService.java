package com.example.tradingplatform.auth;

import com.example.tradingplatform.logging.AuditLogService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    public static final String ROLE_TRADER = "TRADER";
    public static final String ROLE_KYC_REVIEWER = "KYC_REVIEWER";
    public static final String ROLE_AUDITOR = "AUDITOR";
    public static final String ROLE_MARKET = "MARKET";

    private final AuditLogService auditLogService;
    private final Map<String, AccountRecord> usersById = new ConcurrentHashMap<>();
    private final Map<String, String> userIdsByUsername = new ConcurrentHashMap<>();
    private final Map<String, String> userIdsByToken = new ConcurrentHashMap<>();
    private final Map<String, MutableBalance> balancesByUserId = new ConcurrentHashMap<>();

    public AuthService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
        seedUser("admin", "admin", Set.of(ROLE_KYC_REVIEWER, ROLE_AUDITOR), KycStatus.APPROVED, "Seed admin account");
        seedUser("regulator", "regulator", Set.of(ROLE_AUDITOR), KycStatus.APPROVED, "Seed regulator account");
        seedUser("market", "market", Set.of(ROLE_MARKET), KycStatus.APPROVED, "Internal market maker account");
    }

    public User registerTrader(String username, String password, String kycText) {
        if (userIdsByUsername.containsKey(username)) {
            throw new IllegalArgumentException("Username already exists");
        }
        String userId = UUID.randomUUID().toString();
        AccountRecord record = new AccountRecord(userId, username, password, Set.of(ROLE_TRADER), KycStatus.PENDING, kycText, Instant.now());
        usersById.put(userId, record);
        userIdsByUsername.put(username, userId);
        balancesByUserId.put(userId, new MutableBalance(new BigDecimal("100000.00"), new ConcurrentHashMap<>(Map.of("STUB", new BigDecimal("100.00")))));
        auditLogService.write("USER_REGISTERED", Set.of("auth", "kyc"), userId, "Trader registered with pending KYC: " + username);
        return record.toUser();
    }

    public String login(String username, String password) {
        String userId = userIdsByUsername.get(username);
        if (userId == null || !usersById.get(userId).password().equals(password)) {
            auditLogService.write("LOGIN_FAILED", Set.of("auth", "security"), null, "Failed login for username: " + username);
            throw new IllegalArgumentException("Invalid credentials");
        }
        String token = UUID.randomUUID().toString();
        userIdsByToken.put(token, userId);
        auditLogService.write("LOGIN_SUCCEEDED", Set.of("auth", "security"), userId, "Login succeeded for username: " + username);
        return token;
    }

    public AuthenticatedUser authenticate(String token) {
        String userId = userIdsByToken.get(token);
        if (userId == null) {
            throw new IllegalArgumentException("Invalid access token");
        }
        return usersById.get(userId).toAuthenticatedUser();
    }

    public User decideKyc(String reviewerToken, String userId, KycStatus status) {
        AuthenticatedUser reviewer = authenticate(reviewerToken);
        requireRole(reviewer, ROLE_KYC_REVIEWER);
        if (status == KycStatus.PENDING) {
            throw new IllegalArgumentException("KYC decision must be APPROVED or REJECTED");
        }
        AccountRecord existing = requireAccount(userId);
        AccountRecord updated = existing.withKycStatus(status);
        usersById.put(userId, updated);
        auditLogService.write("KYC_" + status.name(), Set.of("auth", "kyc"), reviewer.id(), "KYC " + status.name().toLowerCase() + " for user " + existing.username());
        return updated.toUser();
    }

    public Balance getBalance(String token, String userId) {
        AuthenticatedUser requester = authenticate(token);
        if (!requester.id().equals(userId) && !requester.hasRole(ROLE_AUDITOR)) {
            throw new IllegalArgumentException("Insufficient permissions to read this balance");
        }
        return balanceOf(userId);
    }

    public Balance balanceOf(String userId) {
        MutableBalance balance = requireBalance(userId);
        return new Balance(balance.cash(), Map.copyOf(balance.assets()));
    }

    public void reserveCash(String userId, BigDecimal amount) {
        MutableBalance balance = requireBalance(userId);
        synchronized (balance) {
            if (balance.cash().compareTo(amount) < 0) {
                throw new IllegalArgumentException("Insufficient cash balance");
            }
            balance.setCash(balance.cash().subtract(amount));
        }
    }

    public void releaseCash(String userId, BigDecimal amount) {
        MutableBalance balance = requireBalance(userId);
        synchronized (balance) {
            balance.setCash(balance.cash().add(amount));
        }
    }

    public void reserveAsset(String userId, String instrument, BigDecimal quantity) {
        MutableBalance balance = requireBalance(userId);
        synchronized (balance) {
            BigDecimal available = balance.assets().getOrDefault(instrument, BigDecimal.ZERO);
            if (available.compareTo(quantity) < 0) {
                throw new IllegalArgumentException("Insufficient asset balance");
            }
            balance.assets().put(instrument, available.subtract(quantity));
        }
    }

    public void releaseAsset(String userId, String instrument, BigDecimal quantity) {
        MutableBalance balance = requireBalance(userId);
        synchronized (balance) {
            balance.assets().merge(instrument, quantity, BigDecimal::add);
        }
    }

    public void applyTrade(String buyerUserId, String sellerUserId, String instrument, BigDecimal quantity, BigDecimal price) {
        BigDecimal cash = price.multiply(quantity);
        releaseAsset(buyerUserId, instrument, quantity);
        releaseCash(sellerUserId, cash);
        auditLogService.write("BALANCE_CHANGED", Set.of("auth", "trading", "balance"), buyerUserId, "Buyer received " + quantity + " " + instrument);
        auditLogService.write("BALANCE_CHANGED", Set.of("auth", "trading", "balance"), sellerUserId, "Seller received " + cash + " cash");
    }

    public List<User> listUsers(String token) {
        AuthenticatedUser requester = authenticate(token);
        requireRole(requester, ROLE_AUDITOR);
        return usersById.values().stream().map(AccountRecord::toUser).collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    public Optional<User> findFirstByRole(String role) {
        return usersById.values().stream()
                .filter(user -> user.roles().contains(role))
                .findFirst()
                .map(AccountRecord::toUser);
    }

    public Optional<User> findUser(String userId) {
        return Optional.ofNullable(usersById.get(userId)).map(AccountRecord::toUser);
    }

    public void requireRole(AuthenticatedUser user, String role) {
        if (!user.hasRole(role)) {
            throw new IllegalArgumentException("Required role missing: " + role);
        }
    }

    private AccountRecord requireAccount(String userId) {
        AccountRecord record = usersById.get(userId);
        if (record == null) {
            throw new IllegalArgumentException("User not found");
        }
        return record;
    }

    private MutableBalance requireBalance(String userId) {
        MutableBalance balance = balancesByUserId.get(userId);
        if (balance == null) {
            throw new IllegalArgumentException("Balance not found");
        }
        return balance;
    }

    private void seedUser(String username, String password, Set<String> roles, KycStatus status, String kycText) {
        String userId = UUID.randomUUID().toString();
        AccountRecord record = new AccountRecord(userId, username, password, roles, status, kycText, Instant.now());
        usersById.put(userId, record);
        userIdsByUsername.put(username, userId);
        balancesByUserId.put(userId, new MutableBalance(new BigDecimal("1000000.00"), new ConcurrentHashMap<>(Map.of("STUB", new BigDecimal("1000000.00")))));
    }

    private record AccountRecord(String id, String username, String password, Set<String> roles, KycStatus kycStatus, String kycText, Instant createdAt) {
        User toUser() {
            return new User(id, username, roles, kycStatus, kycText, createdAt);
        }

        AuthenticatedUser toAuthenticatedUser() {
            return new AuthenticatedUser(id, username, roles, kycStatus);
        }

        AccountRecord withKycStatus(KycStatus status) {
            return new AccountRecord(id, username, password, roles, status, kycText, createdAt);
        }
    }

    private static final class MutableBalance {
        private BigDecimal cash;
        private final Map<String, BigDecimal> assets;

        private MutableBalance(BigDecimal cash, Map<String, BigDecimal> assets) {
            this.cash = cash;
            this.assets = assets;
        }

        BigDecimal cash() {
            return cash;
        }

        void setCash(BigDecimal cash) {
            this.cash = cash;
        }

        Map<String, BigDecimal> assets() {
            return assets;
        }
    }
}
