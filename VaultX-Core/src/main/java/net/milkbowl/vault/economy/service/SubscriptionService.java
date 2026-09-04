package net.milkbowl.vault.economy.service;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.VaultSubscriptionAPI;
import net.milkbowl.vault.redis.LocalFailoverManager;
import org.bukkit.OfflinePlayer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Handles subscription registration and cancellation.
 */
public class SubscriptionService implements VaultSubscriptionAPI {

    private final ExecutorService asyncExecutor;

    public SubscriptionService(ExecutorService asyncExecutor) {
        this.asyncExecutor = asyncExecutor;
    }

    public CompletableFuture<Boolean> registerSubscriptionAsync(
            OfflinePlayer player,
            String subscriptionId,
            String currency,
            double amount,
            long intervalMs) {
        return CompletableFuture.supplyAsync(() -> {
            if (player == null || subscriptionId == null || amount <= 0 || intervalMs <= 0) {
                return false;
            }
            LocalFailoverManager fm = Vault.getFailoverManager();
            if (fm == null) return false;
            int intervalHours = (int) Math.max(1, intervalMs / 3600000L);
            long now = System.currentTimeMillis();
            long nextBilling = now + intervalMs;
            LocalFailoverManager.SubscriptionRecord sub = new LocalFailoverManager.SubscriptionRecord(
                    subscriptionId, player.getUniqueId(), "SYSTEM", "SYSTEM", amount,
                    currency == null ? "default" : currency, intervalHours, now, nextBilling, "ACTIVE", now);
            fm.saveSubscription(sub);
            return true;
        }, asyncExecutor);
    }

    public CompletableFuture<Boolean> cancelSubscriptionAsync(String subscriptionId) {
        return CompletableFuture.supplyAsync(() -> {
            if (subscriptionId == null) {
                return false;
            }
            LocalFailoverManager fm = Vault.getFailoverManager();
            if (fm == null) return false;
            fm.deleteSubscription(subscriptionId);
            return true;
        }, asyncExecutor);
    }
}
