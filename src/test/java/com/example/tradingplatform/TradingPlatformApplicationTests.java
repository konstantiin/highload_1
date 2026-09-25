package com.example.tradingplatform;

import com.example.tradingplatform.auth.AuthService;
import com.example.tradingplatform.auth.Balance;
import com.example.tradingplatform.auth.KycStatus;
import com.example.tradingplatform.auth.User;
import com.example.tradingplatform.logging.AuditLogService;
import com.example.tradingplatform.market.MarketService;
import com.example.tradingplatform.trading.Order;
import com.example.tradingplatform.trading.OrderSide;
import com.example.tradingplatform.trading.OrderStatus;
import com.example.tradingplatform.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TradingPlatformApplicationTests {
    @Autowired
    AuthService authService;

    @Autowired
    TradingService tradingService;

    @Autowired
    MarketService marketService;

    @Autowired
    AuditLogService auditLogService;

    @Test
    void pendingKycTraderCannotPlaceOrder() {
        authService.registerTrader("pending-user", "secret", "kyc data");
        String token = authService.login("pending-user", "secret");

        assertThatThrownBy(() -> tradingService.placeOrder(token, "STUB", OrderSide.BUY, new BigDecimal("10.00"), BigDecimal.ONE))
                .hasMessageContaining("KYC must be approved");
    }

    @Test
    void approvedLimitOrdersMatchAndUpdateBalances() {
        User buyer = authService.registerTrader("buyer", "secret", "kyc data");
        User seller = authService.registerTrader("seller", "secret", "kyc data");
        String adminToken = authService.login("admin", "admin");
        authService.decideKyc(adminToken, buyer.id(), KycStatus.APPROVED);
        authService.decideKyc(adminToken, seller.id(), KycStatus.APPROVED);

        String buyerToken = authService.login("buyer", "secret");
        String sellerToken = authService.login("seller", "secret");

        tradingService.placeOrder(sellerToken, "STUB", OrderSide.SELL, new BigDecimal("90.00"), new BigDecimal("2.00"));
        Order buy = tradingService.placeOrder(buyerToken, "STUB", OrderSide.BUY, new BigDecimal("100.00"), new BigDecimal("2.00"));

        assertThat(tradingService.listTrades(buyerToken)).hasSize(1);
        assertThat(tradingService.listOrders(buyerToken).get(0).status()).isEqualTo(OrderStatus.FILLED);
        assertThat(tradingService.listOrders(sellerToken).get(0).status()).isEqualTo(OrderStatus.FILLED);

        Balance buyerBalance = authService.getBalance(buyerToken, buyer.id());
        Balance sellerBalance = authService.getBalance(sellerToken, seller.id());

        assertThat(buyerBalance.assets().get("STUB")).isEqualByComparingTo("102.00");
        assertThat(buyerBalance.cash()).isEqualByComparingTo("99820.00");
        assertThat(sellerBalance.assets().get("STUB")).isEqualByComparingTo("98.00");
        assertThat(sellerBalance.cash()).isEqualByComparingTo("100180.00");
        assertThat(buy.status()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void auditLogCanBeFilteredByTags() {
        auditLogService.write("TEST_EVENT", Set.of("test", "audit"), "actor", "message");

        assertThat(auditLogService.query(null, null, Set.of("test"))).anyMatch(event -> event.type().equals("TEST_EVENT"));
        assertThat(auditLogService.query(null, null, Set.of("missing"))).noneMatch(event -> event.type().equals("TEST_EVENT"));
    }

    @Test
    void marketMakerPlacesSyntheticOrderIntoOrderBook() {
        var result = marketService.tick("STUB");

        assertThat(result.syntheticOrder().synthetic()).isTrue();
        assertThat(tradingService.stats("STUB").openOrderCount()).isGreaterThanOrEqualTo(1);
    }
}
