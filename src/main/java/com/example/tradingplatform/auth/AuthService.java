package com.example.tradingplatform.auth;

import com.example.tradingplatform.auth.persistence.AccessTokenEntity;
import com.example.tradingplatform.auth.persistence.AccessTokenRepository;
import com.example.tradingplatform.auth.persistence.UserEntity;
import com.example.tradingplatform.auth.persistence.UserRepository;
import com.example.tradingplatform.logging.AuditLogService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {
    public static final String ROLE_TRADER = "TRADER";
    public static final String ROLE_AUDITOR = "AUDITOR";
    public static final String ROLE_MARKET = "MARKET";

    private final AuditLogService auditLogService;
    private final UserRepository userRepository;
    private final AccessTokenRepository accessTokenRepository;

    public AuthService(AuditLogService auditLogService, UserRepository userRepository, AccessTokenRepository accessTokenRepository) {
        this.auditLogService = auditLogService;
        this.userRepository = userRepository;
        this.accessTokenRepository = accessTokenRepository;
    }

    @PostConstruct
    @Transactional
    public void seedUsers() {
        seedUser("admin", "admin", Set.of(ROLE_AUDITOR));
        seedUser("regulator", "regulator", Set.of(ROLE_AUDITOR));
        seedUser("market", "market", Set.of(ROLE_MARKET));
    }

    @Transactional
    public User registerTrader(String username, String password) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists");
        }
        UserEntity user = new UserEntity(
                UUID.randomUUID().toString(),
                username,
                password,
                Set.of(ROLE_TRADER),
                new BigDecimal("100000.00"),
                Map.of("STUB", new BigDecimal("100.00")),
                Instant.now()
        );
        UserEntity saved = userRepository.save(user);
        auditLogService.write("USER_REGISTERED", Set.of("auth"), saved.getId(), "Trader registered: " + username);
        return toUser(saved);
    }

    @Transactional
    public String login(String username, String password) {
        Optional<UserEntity> user = userRepository.findByUsername(username);
        if (user.isEmpty() || !user.get().getPassword().equals(password)) {
            auditLogService.write("LOGIN_FAILED", Set.of("auth", "security"), null, "Failed login for username: " + username);
            throw new IllegalArgumentException("Invalid credentials");
        }
        String token = UUID.randomUUID().toString();
        accessTokenRepository.save(new AccessTokenEntity(token, user.get(), Instant.now()));
        auditLogService.write("LOGIN_SUCCEEDED", Set.of("auth", "security"), user.get().getId(), "Login succeeded for username: " + username);
        return token;
    }

    @Transactional(readOnly = true)
    public AuthenticatedUser authenticate(String token) {
        AccessTokenEntity accessToken = accessTokenRepository.findById(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid access token"));
        return toAuthenticatedUser(accessToken.getUser());
    }

    @Transactional(readOnly = true)
    public Balance getBalance(String token, String userId) {
        AuthenticatedUser requester = authenticate(token);
        if (!requester.id().equals(userId) && !requester.hasRole(ROLE_AUDITOR)) {
            throw new IllegalArgumentException("Insufficient permissions to read this balance");
        }
        return balanceOf(userId);
    }

    @Transactional(readOnly = true)
    public Balance balanceOf(String userId) {
        return toBalance(requireAccount(userId));
    }

    @Transactional
    public synchronized void reserveCash(String userId, BigDecimal amount) {
        UserEntity user = requireAccount(userId);
        if (user.getCash().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient cash balance");
        }
        user.setCash(user.getCash().subtract(amount));
        userRepository.save(user);
    }

    @Transactional
    public synchronized void releaseCash(String userId, BigDecimal amount) {
        UserEntity user = requireAccount(userId);
        user.setCash(user.getCash().add(amount));
        userRepository.save(user);
    }

    @Transactional
    public synchronized void reserveAsset(String userId, String instrument, BigDecimal quantity) {
        UserEntity user = requireAccount(userId);
        BigDecimal available = user.getAssets().getOrDefault(instrument, BigDecimal.ZERO);
        if (available.compareTo(quantity) < 0) {
            throw new IllegalArgumentException("Insufficient asset balance");
        }
        user.getAssets().put(instrument, available.subtract(quantity));
        userRepository.save(user);
    }

    @Transactional
    public synchronized void releaseAsset(String userId, String instrument, BigDecimal quantity) {
        UserEntity user = requireAccount(userId);
        user.getAssets().merge(instrument, quantity, BigDecimal::add);
        userRepository.save(user);
    }

    @Transactional
    public synchronized void applyTrade(String buyerUserId, String sellerUserId, String instrument, BigDecimal quantity, BigDecimal price) {
        BigDecimal cash = price.multiply(quantity);
        releaseAsset(buyerUserId, instrument, quantity);
        releaseCash(sellerUserId, cash);
        auditLogService.write("BALANCE_CHANGED", Set.of("auth", "trading", "balance"), buyerUserId, "Buyer received " + quantity + " " + instrument);
        auditLogService.write("BALANCE_CHANGED", Set.of("auth", "trading", "balance"), sellerUserId, "Seller received " + cash + " cash");
    }

    @Transactional(readOnly = true)
    public List<User> listUsers(String token) {
        AuthenticatedUser requester = authenticate(token);
        requireRole(requester, ROLE_AUDITOR);
        return userRepository.findAll().stream().map(this::toUser).toList();
    }

    @Transactional(readOnly = true)
    public Optional<User> findFirstByRole(String role) {
        return userRepository.findAll().stream()
                .filter(user -> user.getRoles().contains(role))
                .findFirst()
                .map(this::toUser);
    }

    @Transactional(readOnly = true)
    public Optional<User> findUser(String userId) {
        return userRepository.findById(userId).map(this::toUser);
    }

    public void requireRole(AuthenticatedUser user, String role) {
        if (!user.hasRole(role)) {
            throw new IllegalArgumentException("Required role missing: " + role);
        }
    }

    private UserEntity requireAccount(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private void seedUser(String username, String password, Set<String> roles) {
        if (userRepository.existsByUsername(username)) {
            return;
        }
        userRepository.save(new UserEntity(
                UUID.randomUUID().toString(),
                username,
                password,
                roles,
                new BigDecimal("1000000.00"),
                Map.of("STUB", new BigDecimal("1000000.00")),
                Instant.now()
        ));
    }

    private User toUser(UserEntity user) {
        return new User(user.getId(), user.getUsername(), Set.copyOf(user.getRoles()), user.getCreatedAt());
    }

    private AuthenticatedUser toAuthenticatedUser(UserEntity user) {
        return new AuthenticatedUser(user.getId(), user.getUsername(), Set.copyOf(user.getRoles()));
    }

    private Balance toBalance(UserEntity user) {
        return new Balance(user.getCash(), Map.copyOf(user.getAssets()));
    }
}
