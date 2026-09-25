package com.example.tradingplatform;

import com.example.tradingplatform.auth.AuthService;
import com.example.tradingplatform.auth.Balance;
import com.example.tradingplatform.auth.User;
import com.example.tradingplatform.logging.AuditLogService;
import com.example.tradingplatform.trading.Order;
import com.example.tradingplatform.trading.OrderSide;
import com.example.tradingplatform.trading.OrderStatus;
import com.example.tradingplatform.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "market.stub-generator.initial-delay=600000")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class TradingPlatformApplicationTests {
    @Autowired
    AuthService authService;

    @Autowired
    TradingService tradingService;

    @Autowired
    AuditLogService auditLogService;

    @Test
    void registeredTraderCanPlaceOrderImmediately() {
        authService.registerTrader("new-trader", "secret");

        Order order = tradingService.placeOrder("new-trader", "secret", "STUB", OrderSide.BUY, new BigDecimal("10.00"), BigDecimal.ONE);

        assertThat(order.status()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void approvedLimitOrdersMatchAndUpdateBalances() {
        User buyer = authService.registerTrader("buyer", "secret");
        User seller = authService.registerTrader("seller", "secret");

        tradingService.placeOrder("seller", "secret", "STUB", OrderSide.SELL, new BigDecimal("90.00"), new BigDecimal("2.00"));
        Order buy = tradingService.placeOrder("buyer", "secret", "STUB", OrderSide.BUY, new BigDecimal("100.00"), new BigDecimal("2.00"));

        assertThat(tradingService.listTrades("buyer", "secret")).hasSize(1);
        assertThat(tradingService.listOrders("buyer", "secret").get(0).status()).isEqualTo(OrderStatus.FILLED);
        assertThat(tradingService.listOrders("seller", "secret").get(0).status()).isEqualTo(OrderStatus.FILLED);

        Balance buyerBalance = authService.getBalance("buyer", "secret", buyer.id());
        Balance sellerBalance = authService.getBalance("seller", "secret", seller.id());

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

}
