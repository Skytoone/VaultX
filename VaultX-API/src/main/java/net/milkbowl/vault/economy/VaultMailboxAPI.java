package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Enterprise API for sending offline payments and mailbox rewards to players safely.
 */
public interface VaultMailboxAPI {

    /**
     * Sends an offline payment to a target player asynchronously.
     * @param targetUuid Target player UUID
     * @param currency Currency ID
     * @param amount Amount to send
     * @param sourceReason Reason or source description (e.g. "AuctionHouse Payout #102")
     * @return Future containing true if successful
     */
    CompletableFuture<Boolean> sendOfflinePaymentAsync(UUID targetUuid, String currency, double amount, String sourceReason);

    /**
     * Sends an offline payment to a target player asynchronously.
     * @param target Target OfflinePlayer
     * @param currency Currency ID
     * @param amount Amount to send
     * @param sourceReason Reason or source description
     * @return Future containing true if successful
     */
    CompletableFuture<Boolean> sendOfflinePaymentAsync(OfflinePlayer target, String currency, double amount, String sourceReason);
}
