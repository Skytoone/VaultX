package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Smart Contract & Conditional Escrow Bounties API for VaultX.
 */
public interface VaultSmartContractAPI {

    enum ContractType {
        BOUNTY,
        QUEST,
        SERVICE
    }

    enum ContractStatus {
        ACTIVE,
        FULFILLED,
        CANCELLED,
        EXPIRED
    }

    class SmartContract {
        public final String contractId;
        public final UUID creatorUuid;
        public final UUID targetUuid;
        public final ContractType type;
        public final double lockedAmount;
        public final String currency;
        public final long expiresAt;
        public final ContractStatus status;

        public SmartContract(String contractId, UUID creatorUuid, UUID targetUuid, ContractType type, double lockedAmount, String currency, long expiresAt, ContractStatus status) {
            this.contractId = contractId;
            this.creatorUuid = creatorUuid;
            this.targetUuid = targetUuid;
            this.type = type;
            this.lockedAmount = lockedAmount;
            this.currency = currency;
            this.expiresAt = expiresAt;
            this.status = status;
        }
    }

    class ContractResult {
        public final boolean success;
        public final String message;
        public final String contractId;

        public ContractResult(boolean success, String message, String contractId) {
            this.success = success;
            this.message = message;
            this.contractId = contractId;
        }
    }

    CompletableFuture<ContractResult> createBountyContractAsync(OfflinePlayer creator, OfflinePlayer targetPlayer, double rewardAmount, String currency, long durationSeconds);

    CompletableFuture<ContractResult> createQuestContractAsync(OfflinePlayer creator, String questId, double rewardAmount, String currency, long durationSeconds);

    CompletableFuture<ContractResult> claimContractAsync(String contractId, OfflinePlayer claimant);

    CompletableFuture<ContractResult> cancelContractAsync(String contractId, OfflinePlayer creator);

    CompletableFuture<List<SmartContract>> getActiveContractsAsync();
}
