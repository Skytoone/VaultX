package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Enterprise-grade Escrow API for secure, delayed-release player transactions.
 */
public interface VaultEscrowAPI {

    class EscrowDetails {
        public final String id;
        public final UUID sender;
        public final UUID receiver;
        public final double amount;
        public final String currency;
        public final String status;
        public final long timeoutAt;

        public EscrowDetails(String id, UUID sender, UUID receiver, double amount, String currency, String status, long timeoutAt) {
            this.id = id;
            this.sender = sender;
            this.receiver = receiver;
            this.amount = amount;
            this.currency = currency;
            this.status = status;
            this.timeoutAt = timeoutAt;
        }
    }

    class EscrowResult {
        public final boolean success;
        public final String message;
        public final String escrowId;

        public EscrowResult(boolean success, String message, String escrowId) {
            this.success = success;
            this.message = message;
            this.escrowId = escrowId;
        }
    }

    CompletableFuture<EscrowResult> startEscrow(Player sender, OfflinePlayer receiver, double amount, String currency, long timeoutSec);

    CompletableFuture<EscrowResult> releaseEscrow(String escrowId, CommandSender actor);

    CompletableFuture<EscrowResult> refundEscrow(String escrowId, CommandSender actor);

    CompletableFuture<List<EscrowDetails>> getPlayerEscrowsAsync(OfflinePlayer player);
}
