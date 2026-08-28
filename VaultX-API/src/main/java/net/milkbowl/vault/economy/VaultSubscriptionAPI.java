package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for registering and managing recurring subscription payments (e.g. Towny land taxes, VIP rent, guild dues).
 */
public interface VaultSubscriptionAPI {

    record SubscriptionDetails(String subscriptionId, UUID playerUuid, String currency, double amount, long intervalMs, long nextRenewalMs) {}

    /**
     * Registers a recurring subscription payment for a player.
     * @param player OfflinePlayer
     * @param subscriptionId Unique subscription identifier (e.g. "towny_tax_plot_42")
     * @param currency Currency ID
     * @param amount Amount to deduct on each interval
     * @param intervalMs Interval between renewals in milliseconds
     * @return Future containing true if registered successfully
     */
    CompletableFuture<Boolean> registerSubscriptionAsync(OfflinePlayer player, String subscriptionId, String currency, double amount, long intervalMs);

    /**
     * Cancels an active recurring subscription payment.
     * @param subscriptionId Unique subscription identifier
     * @return Future containing true if cancelled successfully
     */
    CompletableFuture<Boolean> cancelSubscriptionAsync(String subscriptionId);
}
