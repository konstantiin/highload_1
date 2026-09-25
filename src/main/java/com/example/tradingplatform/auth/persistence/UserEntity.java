package com.example.tradingplatform.auth.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "app_users")
public class UserEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private String id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false)
    private Set<String> roles = new HashSet<>();

    @Column(nullable = false)
    private BigDecimal cash;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "asset_balances", joinColumns = @JoinColumn(name = "user_id"))
    @MapKeyColumn(name = "instrument")
    @Column(name = "quantity", nullable = false)
    private Map<String, BigDecimal> assets = new HashMap<>();

    @Column(nullable = false)
    private Instant createdAt;

    protected UserEntity() {
    }

    public UserEntity(String id, String username, String password, Set<String> roles, BigDecimal cash, Map<String, BigDecimal> assets, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.roles = new HashSet<>(roles);
        this.cash = cash;
        this.assets = new HashMap<>(assets);
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public BigDecimal getCash() {
        return cash;
    }

    public void setCash(BigDecimal cash) {
        this.cash = cash;
    }

    public Map<String, BigDecimal> getAssets() {
        return assets;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
