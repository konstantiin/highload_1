package com.example.tradingplatform;

import com.example.tradingplatform.auth.AuthService;
import com.example.tradingplatform.auth.Balance;
import com.example.tradingplatform.auth.User;
import com.example.tradingplatform.trading.MarketStats;
import com.example.tradingplatform.trading.Order;
import com.example.tradingplatform.trading.OrderSide;
import com.example.tradingplatform.trading.OrderStatus;
import com.example.tradingplatform.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "market.stub-generator.initial-delay=600000")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class TradingServiceTests {
    @Autowired
    AuthService authService;

    @Autowired
    TradingService tradingService;

    @Test
    void cancelBuyOrderReleasesReservedCash() {
        String username = unique("cancel-buy");
        User user = authService.registerTrader(username, "secret");
        Order order = tradingService.placeOrder(username, "secret", instrument(), OrderSide.BUY, new BigDecimal("10.00"), new BigDecimal("5.00"));

        Order cancelled = tradingService.cancelOrder(username, "secret", order.id());
        Balance balance = authService.getBalance(username, "secret", user.id());

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(balance.cash()).isEqualByComparingTo("100000.00");
    }

    @Test
    void cancelSellOrderReleasesReservedAsset() {
        String username = unique("cancel-sell");
        User user = authService.registerTrader(username, "secret");
        Order order = tradingService.placeOrder(username, "secret", "STUB", OrderSide.SELL, new BigDecimal("100000.00"), new BigDecimal("5.00"));

        Order cancelled = tradingService.cancelOrder(username, "secret", order.id());
        Balance balance = authService.getBalance(username, "secret", user.id());

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(balance.assets().get(order.instrument())).isEqualByComparingTo("100.00");
    }

    @Test
    void userCannotCancelAnotherUsersOrderButAuditorCan() {
        String owner = unique("owner");
        String stranger = unique("stranger");
        authService.registerTrader(owner, "secret");
        authService.registerTrader(stranger, "secret");
        Order order = tradingService.placeOrder(owner, "secret", instrument(), OrderSide.BUY, new BigDecimal("10.00"), BigDecimal.ONE);

        assertThatThrownBy(() -> tradingService.cancelOrder(stranger, "secret", order.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot cancel another user's order");

        assertThat(tradingService.cancelOrder("admin", "admin", order.id()).status())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void invalidAndUnknownOrdersAreRejected() {
        String username = unique("invalid-order");
        authService.registerTrader(username, "secret");

        assertThatThrownBy(() -> tradingService.placeOrder(username, "secret", instrument(), OrderSide.BUY, BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Price and quantity must be positive");
        assertThatThrownBy(() -> tradingService.cancelOrder(username, "secret", "missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Order not found");
    }

    @Test
    void partialFillLeavesRemainingOrderOpenAndStatsReflectTrade() {
        String buyer = unique("partial-buyer");
        String seller = unique("partial-seller");
        String instrument = "STUB";
        authService.registerTrader(buyer, "secret");
        authService.registerTrader(seller, "secret");

        Order sell = tradingService.placeOrder(seller, "secret", instrument, OrderSide.SELL, new BigDecimal("90.00"), new BigDecimal("5.00"));
        tradingService.placeOrder(buyer, "secret", instrument, OrderSide.BUY, new BigDecimal("95.00"), new BigDecimal("2.00"));

        Order updatedSell = tradingService.listOrders(seller, "secret").stream()
                .filter(order -> order.id().equals(sell.id()))
                .findFirst()
                .orElseThrow();
        MarketStats stats = tradingService.stats(instrument);

        assertThat(updatedSell.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(updatedSell.remainingQuantity()).isEqualByComparingTo("3.00");
        assertThat(stats.averageTradePrice()).isEqualByComparingTo("90.00");
        assertThat(stats.realOpenOrderVolume()).isGreaterThanOrEqualTo(new BigDecimal("3.00"));
    }

    @Test
    void defaultInstrumentIsStubAndExplicitInstrumentIsUppercase() {
        String username = unique("instrument");
        authService.registerTrader(username, "secret");

        Order defaultInstrument = tradingService.placeOrder(username, "secret", null, OrderSide.BUY, BigDecimal.ONE, BigDecimal.ONE);
        Order uppercaseInstrument = tradingService.placeOrder(username, "secret", "abc", OrderSide.BUY, BigDecimal.ONE, BigDecimal.ONE);

        assertThat(defaultInstrument.instrument()).isEqualTo("STUB");
        assertThat(uppercaseInstrument.instrument()).isEqualTo("ABC");
    }

    @Test
    void auditorCanSeeAllOrdersAndTrades() {
        String buyer = unique("auditor-buyer");
        String seller = unique("auditor-seller");
        String instrument = "STUB";
        authService.registerTrader(buyer, "secret");
        authService.registerTrader(seller, "secret");

        tradingService.placeOrder(seller, "secret", instrument, OrderSide.SELL, new BigDecimal("10.00"), BigDecimal.ONE);
        tradingService.placeOrder(buyer, "secret", instrument, OrderSide.BUY, new BigDecimal("10.00"), BigDecimal.ONE);

        assertThat(tradingService.listOrders("admin", "admin")).anyMatch(order -> order.instrument().equals(instrument));
        assertThat(tradingService.listTrades("admin", "admin")).anyMatch(trade -> trade.instrument().equals(instrument));
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String instrument() {
        return "I" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
