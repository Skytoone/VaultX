package net.milkbowl.vault.economy.service;

import net.milkbowl.vault.economy.VaultAuditAPI;
import net.milkbowl.vault.redis.LocalFailoverManager;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Specialized service for auditing and querying transaction history logs.
 */
public class AuditService implements VaultAuditAPI {

    private final ExecutorService asyncExecutor;

    public AuditService(ExecutorService asyncExecutor) {
        this.asyncExecutor = asyncExecutor;
    }

    private LocalFailoverManager failover() {
        return net.milkbowl.vault.Vault.getFailoverManager();
    }

    @Override
    public CompletableFuture<List<AuditLogEntry>> getPlayerTransactionHistoryAsync(OfflinePlayer player, int limit) {
        return player != null ? getPlayerTransactionHistoryAsync(player.getUniqueId(), limit)
                : CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public CompletableFuture<List<AuditLogEntry>> getPlayerTransactionHistoryAsync(UUID playerUuid, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<AuditLogEntry> logs = new ArrayList<>();
            LocalFailoverManager fm = failover();
            if (playerUuid != null && fm != null) {
                var records = fm.getPlayerTransactions(playerUuid, 1, limit);
                if (records != null) {
                    for (var r : records) {
                        UUID u = r.uuid != null ? UUID.fromString(r.uuid) : playerUuid;
                        logs.add(new AuditLogEntry(u, r.type, r.currency, r.amount, r.otherParty, r.timestamp));
                    }
                }
            }
            return logs;
        }, asyncExecutor);
    }
}
