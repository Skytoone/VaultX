package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for auditing and querying transaction history logs for security and moderation.
 */
public interface VaultAuditAPI {

    record AuditLogEntry(UUID playerUuid, String type, String currency, double amount, String callerPlugin, long timestamp) {}

    /**
     * Queries past transaction history logs for a player.
     * @param player OfflinePlayer
     * @param limit Maximum number of records to retrieve
     * @return Future containing list of AuditLogEntry
     */
    CompletableFuture<List<AuditLogEntry>> getPlayerTransactionHistoryAsync(OfflinePlayer player, int limit);

    /**
     * Queries past transaction history logs for a player by UUID.
     * @param playerUuid Player UUID
     * @param limit Maximum number of records to retrieve
     * @return Future containing list of AuditLogEntry
     */
    CompletableFuture<List<AuditLogEntry>> getPlayerTransactionHistoryAsync(UUID playerUuid, int limit);
}
