package com.example.tradingplatform;

import com.example.tradingplatform.auth.AuthService;
import com.example.tradingplatform.auth.Balance;
import com.example.tradingplatform.auth.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "market.stub-generator.initial-delay=600000")
class AuthServiceTests {
    @Autowired
    AuthService authService;

    @Test
    void registerCreatesTraderWithInitialBalances() {
        String username = unique("trader");

        User user = authService.registerTrader(username, "secret");
        Balance balance = authService.getBalance(username, "secret", user.id());

        assertThat(user.username()).isEqualTo(username);
        assertThat(user.roles()).containsExactly(AuthService.ROLE_TRADER);
        assertThat(balance.cash()).isEqualByComparingTo("100000.00");
        assertThat(balance.assets().get("STUB")).isEqualByComparingTo("100.00");
    }

    @Test
    void duplicateUsernameIsRejected() {
        String username = unique("duplicate");
        authService.registerTrader(username, "secret");

        assertThatThrownBy(() -> authService.registerTrader(username, "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username already exists");
    }

    @Test
    void invalidCredentialsAreRejected() {
        String username = unique("bad-password");
        authService.registerTrader(username, "secret");

        assertThatThrownBy(() -> authService.authenticate(username, "wrong"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid credentials");
    }

    @Test
    void traderCannotReadAnotherUsersBalanceButAuditorCan() {
        User owner = authService.registerTrader(unique("owner"), "secret");
        String stranger = unique("stranger");
        authService.registerTrader(stranger, "secret");

        assertThatThrownBy(() -> authService.getBalance(stranger, "secret", owner.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient permissions");

        assertThat(authService.getBalance("admin", "admin", owner.id()).cash())
                .isEqualByComparingTo("100000.00");
    }

    @Test
    void auditorCanListUsersAndTraderCannot() {
        String username = unique("visible");
        authService.registerTrader(username, "secret");

        assertThat(authService.listUsers("admin", "admin"))
                .anyMatch(user -> user.username().equals(username));
        assertThatThrownBy(() -> authService.listUsers(username, "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Required role missing");
    }

    @Test
    void insufficientBalancesAreRejected() {
        User user = authService.registerTrader(unique("poor"), "secret");

        assertThatThrownBy(() -> authService.reserveCash(user.id(), new BigDecimal("1000000.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient cash");
        assertThatThrownBy(() -> authService.reserveAsset(user.id(), "STUB", new BigDecimal("1000.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient asset");
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
