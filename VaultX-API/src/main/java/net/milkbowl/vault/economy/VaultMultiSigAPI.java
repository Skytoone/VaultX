package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Enterprise Multi-Signature & Shared Joint Account API for VaultX.
 */
public interface VaultMultiSigAPI {

    class MultiSigAccountDetails {
        public final String accountId;
        public final String name;
        public final String currency;
        public final double balance;
        public final int requiredSignatures;
        public final List<UUID> memberUuids;
        public final Map<String, Double> rankDailyLimits;

        public MultiSigAccountDetails(String accountId, String name, String currency, double balance, int requiredSignatures, List<UUID> memberUuids, Map<String, Double> rankDailyLimits) {
            this.accountId = accountId;
            this.name = name;
            this.currency = currency;
            this.balance = balance;
            this.requiredSignatures = requiredSignatures;
            this.memberUuids = memberUuids;
            this.rankDailyLimits = rankDailyLimits;
        }
    }

    class PendingMultiSigTransaction {
        public final String txId;
        public final String accountId;
        public final UUID requesterUuid;
        public final UUID targetUuid;
        public final double amount;
        public final String currency;
        public final List<UUID> currentSigners;
        public final int requiredSignatures;
        public final long expiresAt;

        public PendingMultiSigTransaction(String txId, String accountId, UUID requesterUuid, UUID targetUuid, double amount, String currency, List<UUID> currentSigners, int requiredSignatures, long expiresAt) {
            this.txId = txId;
            this.accountId = accountId;
            this.requesterUuid = requesterUuid;
            this.targetUuid = targetUuid;
            this.amount = amount;
            this.currency = currency;
            this.currentSigners = currentSigners;
            this.requiredSignatures = requiredSignatures;
            this.expiresAt = expiresAt;
        }
    }

    class MultiSigResult {
        public final boolean success;
        public final String message;
        public final String txId;

        public MultiSigResult(boolean success, String message, String txId) {
            this.success = success;
            this.message = message;
            this.txId = txId;
        }
    }

    CompletableFuture<MultiSigResult> createAccountAsync(String accountName, String currency, List<UUID> initialMembers, int requiredSignatures);

    CompletableFuture<MultiSigResult> requestWithdrawalAsync(String accountId, OfflinePlayer requester, OfflinePlayer recipient, double amount);

    CompletableFuture<MultiSigResult> signTransactionAsync(String txId, OfflinePlayer signer);

    CompletableFuture<MultiSigResult> cancelTransactionAsync(String txId, OfflinePlayer requester);

    CompletableFuture<List<PendingMultiSigTransaction>> getPendingTransactionsAsync(String accountId);

    CompletableFuture<MultiSigAccountDetails> getAccountDetailsAsync(String accountId);
}
