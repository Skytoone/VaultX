package net.milkbowl.vault.economy.service;

import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.economy.VaultBatchTransactionAPI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Specialized service for executing atomic transaction batches with automated rollback.
 */
public class BatchTransactionService implements VaultBatchTransactionAPI {

    private final MultiCurrencyEconomy economy;
    private final ExecutorService asyncExecutor;

    public BatchTransactionService(MultiCurrencyEconomy economy, ExecutorService asyncExecutor) {
        this.economy = economy;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public CompletableFuture<BatchResult> executeAtomicBatchAsync(List<BatchOperation> operations) {
        return CompletableFuture.supplyAsync(() -> {
            if (operations == null || operations.isEmpty()) {
                return new BatchResult(true, "Empty operations list", Collections.emptyList());
            }
            List<EconomyResponse> responses = new ArrayList<>();
            List<BatchOperation> executed = new ArrayList<>();

            for (BatchOperation op : operations) {
                EconomyResponse resp;
                if (op.type() == OperationType.DEPOSIT) {
                    resp = economy.depositCurrencyPlayer(op.player(), op.currency(), op.amount());
                } else {
                    resp = economy.withdrawCurrencyPlayer(op.player(), op.currency(), op.amount());
                }
                responses.add(resp);

                if (!resp.transactionSuccess()) {
                    // ROLLBACK executed operations
                    for (BatchOperation exec : executed) {
                        if (exec.type() == OperationType.DEPOSIT) {
                            economy.withdrawCurrencyPlayer(exec.player(), exec.currency(), exec.amount());
                        } else {
                            economy.depositCurrencyPlayer(exec.player(), exec.currency(), exec.amount());
                        }
                    }
                    return new BatchResult(false,
                            "Operation failed: " + resp.errorMessage + ". All batch operations rolled back.",
                            responses);
                }
                executed.add(op);
            }
            return new BatchResult(true, null, responses);
        }, asyncExecutor);
    }
}
