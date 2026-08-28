package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Enterprise API for executing atomic transaction batches with automated rollback.
 */
public interface VaultBatchTransactionAPI {

    enum OperationType {
        DEPOSIT,
        WITHDRAW
    }

    record BatchOperation(OfflinePlayer player, String currency, double amount, OperationType type) {}

    record BatchResult(boolean success, String errorMessage, List<EconomyResponse> executedResponses) {}

    /**
     * Executes a batch of operations atomically. If any operation fails, all prior operations are automatically rolled back.
     * @param operations List of operations to execute
     * @return Future containing BatchResult
     */
    CompletableFuture<BatchResult> executeAtomicBatchAsync(List<BatchOperation> operations);
}
