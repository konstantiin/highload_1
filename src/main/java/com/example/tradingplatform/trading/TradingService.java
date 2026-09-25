package com.example.tradingplatform.trading;

import com.example.tradingplatform.auth.AuthService;
import com.example.tradingplatform.auth.AuthenticatedUser;
import com.example.tradingplatform.auth.KycStatus;
import com.example.tradingplatform.logging.AuditLogService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TradingService {
    private static final String DEFAULT_INSTRUMENT = "STUB";

    private final AuthService authService;
    private final AuditLogService auditLogService;
    private final Map<String, Order> ordersById = new ConcurrentHashMap<>();
    private final List<Trade> trades = new ArrayList<>();

    public TradingService(AuthService authService, AuditLogService auditLogService) {
        this.authService = authService;
        this.auditLogService = auditLogService;
    }

    public synchronized Order placeOrder(String token, String instrument, OrderSide side, BigDecimal price, BigDecimal quantity) {
        AuthenticatedUser user = authService.authenticate(token);
        if (user.hasRole(AuthService.ROLE_TRADER) && user.kycStatus() != KycStatus.APPROVED) {
            throw new IllegalArgumentException("KYC must be approved before trading");
        }
        return placeOrderForUser(user.id(), instrument, side, price, quantity, false);
    }

    public synchronized Order placeSyntheticOrder(String marketUserId, String instrument, OrderSide side, BigDecimal price, BigDecimal quantity) {
        return placeOrderForUser(marketUserId, instrument, side, price, quantity, true);
    }

    public synchronized Order cancelOrder(String token, String orderId) {
        AuthenticatedUser user = authService.authenticate(token);
        Order order = requireOrder(orderId);
        if (!order.userId().equals(user.id()) && !user.hasRole(AuthService.ROLE_AUDITOR)) {
            throw new IllegalArgumentException("Cannot cancel another user's order");
        }
        if (order.status() == OrderStatus.FILLED || order.status() == OrderStatus.CANCELLED) {
            return order;
        }
        releaseReservedBalance(order);
        Order cancelled = order.cancelled();
        ordersById.put(orderId, cancelled);
        auditLogService.write("ORDER_CANCELLED", Set.of("trading", "order"), user.id(), "Cancelled order " + orderId);
        return cancelled;
    }

    public List<Order> listOrders(String token) {
        AuthenticatedUser user = authService.authenticate(token);
        if (user.hasRole(AuthService.ROLE_AUDITOR)) {
            return ordersById.values().stream().sorted(Comparator.comparing(Order::createdAt)).toList();
        }
        return ordersById.values().stream()
                .filter(order -> order.userId().equals(user.id()))
                .sorted(Comparator.comparing(Order::createdAt))
                .toList();
    }

    public List<Trade> listTrades(String token) {
        AuthenticatedUser user = authService.authenticate(token);
        if (user.hasRole(AuthService.ROLE_AUDITOR)) {
            return List.copyOf(trades);
        }
        return trades.stream()
                .filter(trade -> trade.buyerUserId().equals(user.id()) || trade.sellerUserId().equals(user.id()))
                .toList();
    }

    public MarketStats stats(String instrument) {
        String selectedInstrument = normalizeInstrument(instrument);
        BigDecimal openRealVolume = ordersById.values().stream()
                .filter(order -> order.instrument().equals(selectedInstrument))
                .filter(order -> !order.synthetic())
                .filter(order -> order.status() == OrderStatus.OPEN || order.status() == OrderStatus.PARTIALLY_FILLED)
                .map(Order::remainingQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Trade> instrumentTrades = trades.stream()
                .filter(trade -> trade.instrument().equals(selectedInstrument))
                .toList();
        BigDecimal averagePrice = instrumentTrades.isEmpty()
                ? new BigDecimal("100.00")
                : instrumentTrades.stream()
                .map(trade -> trade.price().multiply(trade.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(instrumentTrades.stream().map(Trade::quantity).reduce(BigDecimal.ZERO, BigDecimal::add), 2, RoundingMode.HALF_UP);
        int openCount = (int) ordersById.values().stream()
                .filter(order -> order.instrument().equals(selectedInstrument))
                .filter(order -> order.status() == OrderStatus.OPEN || order.status() == OrderStatus.PARTIALLY_FILLED)
                .count();
        return new MarketStats(selectedInstrument, openRealVolume, averagePrice, openCount, instrumentTrades.size());
    }

    private Order placeOrderForUser(String userId, String instrument, OrderSide side, BigDecimal price, BigDecimal quantity, boolean synthetic) {
        validateOrder(price, quantity);
        String selectedInstrument = normalizeInstrument(instrument);
        reserveBalance(userId, selectedInstrument, side, price, quantity);
        Order order = new Order(UUID.randomUUID().toString(), userId, selectedInstrument, side, price, quantity, quantity, OrderStatus.OPEN, synthetic, Instant.now());
        ordersById.put(order.id(), order);
        auditLogService.write(synthetic ? "SYNTHETIC_ORDER_PLACED" : "ORDER_PLACED", Set.of("trading", "order"), userId, "Placed " + side + " order " + order.id());
        match(selectedInstrument);
        return ordersById.get(order.id());
    }

    private void match(String instrument) {
        while (true) {
            Order buy = bestBuy(instrument);
            Order sell = bestSell(instrument);
            if (buy == null || sell == null || buy.price().compareTo(sell.price()) < 0) {
                return;
            }
            BigDecimal quantity = buy.remainingQuantity().min(sell.remainingQuantity());
            BigDecimal executionPrice = sell.createdAt().isBefore(buy.createdAt()) ? sell.price() : buy.price();
            executeTrade(buy, sell, quantity, executionPrice);
        }
    }

    private void executeTrade(Order buy, Order sell, BigDecimal quantity, BigDecimal executionPrice) {
        BigDecimal buyRefund = buy.price().subtract(executionPrice).multiply(quantity);
        if (buyRefund.signum() > 0) {
            authService.releaseCash(buy.userId(), buyRefund);
        }
        authService.applyTrade(buy.userId(), sell.userId(), buy.instrument(), quantity, executionPrice);
        Order updatedBuy = buy.withRemainingQuantity(buy.remainingQuantity().subtract(quantity));
        Order updatedSell = sell.withRemainingQuantity(sell.remainingQuantity().subtract(quantity));
        ordersById.put(updatedBuy.id(), updatedBuy);
        ordersById.put(updatedSell.id(), updatedSell);
        Trade trade = new Trade(UUID.randomUUID().toString(), buy.instrument(), buy.id(), sell.id(), buy.userId(), sell.userId(), executionPrice, quantity, Instant.now());
        trades.add(trade);
        auditLogService.write("TRADE_EXECUTED", Set.of("trading", "trade"), null, "Executed trade " + trade.id() + " for " + quantity + " " + buy.instrument() + " at " + executionPrice);
    }

    private Order bestBuy(String instrument) {
        return ordersById.values().stream()
                .filter(order -> order.instrument().equals(instrument))
                .filter(order -> order.side() == OrderSide.BUY)
                .filter(order -> order.status() == OrderStatus.OPEN || order.status() == OrderStatus.PARTIALLY_FILLED)
                .min(Comparator.comparing(Order::price).reversed().thenComparing(Order::createdAt))
                .orElse(null);
    }

    private Order bestSell(String instrument) {
        return ordersById.values().stream()
                .filter(order -> order.instrument().equals(instrument))
                .filter(order -> order.side() == OrderSide.SELL)
                .filter(order -> order.status() == OrderStatus.OPEN || order.status() == OrderStatus.PARTIALLY_FILLED)
                .min(Comparator.comparing(Order::price).thenComparing(Order::createdAt))
                .orElse(null);
    }

    private void reserveBalance(String userId, String instrument, OrderSide side, BigDecimal price, BigDecimal quantity) {
        if (side == OrderSide.BUY) {
            authService.reserveCash(userId, price.multiply(quantity));
        } else {
            authService.reserveAsset(userId, instrument, quantity);
        }
    }

    private void releaseReservedBalance(Order order) {
        if (order.side() == OrderSide.BUY) {
            authService.releaseCash(order.userId(), order.price().multiply(order.remainingQuantity()));
        } else {
            authService.releaseAsset(order.userId(), order.instrument(), order.remainingQuantity());
        }
    }

    private void validateOrder(BigDecimal price, BigDecimal quantity) {
        if (price == null || quantity == null || price.signum() <= 0 || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Price and quantity must be positive");
        }
    }

    private String normalizeInstrument(String instrument) {
        if (instrument == null || instrument.isBlank()) {
            return DEFAULT_INSTRUMENT;
        }
        return instrument.toUpperCase();
    }

    private Order requireOrder(String orderId) {
        Order order = ordersById.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("Order not found");
        }
        return order;
    }
}
