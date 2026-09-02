package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Standing Orders & Automated Recurring Payments API for VaultX.
 */
public interface VaultStandingOrderAPI {

    enum OrderStatus {
        ACTIVE,
        PAUSED,
        CANCELLED,
        FAILED_INSUFFICIENT_FUNDS
    }

    class StandingOrder {
        public final String orderId;
        public final UUID payerUuid;
        public final UUID payeeUuid;
        public final double amount;
        public final String currency;
        public final long intervalSeconds;
        public final long nextPaymentTime;
        public final OrderStatus status;
        public final int consecutiveFailures;

        public StandingOrder(String orderId, UUID payerUuid, UUID payeeUuid, double amount, String currency, long intervalSeconds, long nextPaymentTime, OrderStatus status, int consecutiveFailures) {
            this.orderId = orderId;
            this.payerUuid = payerUuid;
            this.payeeUuid = payeeUuid;
            this.amount = amount;
            this.currency = currency;
            this.intervalSeconds = intervalSeconds;
            this.nextPaymentTime = nextPaymentTime;
            this.status = status;
            this.consecutiveFailures = consecutiveFailures;
        }
    }

    class OrderResult {
        public final boolean success;
        public final String message;
        public final String orderId;

        public OrderResult(boolean success, String message, String orderId) {
            this.success = success;
            this.message = message;
            this.orderId = orderId;
        }
    }

    CompletableFuture<OrderResult> createOrderAsync(OfflinePlayer payer, OfflinePlayer payee, double amount, String currency, long intervalSeconds);

    CompletableFuture<OrderResult> cancelOrderAsync(String orderId, OfflinePlayer requester);

    CompletableFuture<OrderResult> processOrderAsync(String orderId);

    CompletableFuture<List<StandingOrder>> getPlayerOrdersAsync(OfflinePlayer player);
}
