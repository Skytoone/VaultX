package net.milkbowl.vault.economy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * VaultX Snapshot & Rollback Engine API.
 * Allows taking atomic economy snapshots (point-in-time state of all balances) and performing
 * 1-click server-wide or per-player economy rollbacks following dupe exploits or emergency events.
 */
public interface VaultSnapshotAPI {

    record EconomySnapshot(
        String snapshotId,
        long timestamp,
        String label,
        int totalAccountsCaptured,
        double totalNetWorth
    ) {}

    record PlayerBalanceSnapshot(
        UUID playerUuid,
        String playerName,
        String currency,
        double balance
    ) {}

    /**
     * Creates an atomic point-in-time snapshot of the entire economy state across all currencies.
     * @param label Custom descriptive label for the snapshot (e.g. "Pre-maintenance", "Daily auto-backup", "Post-dupe-incident")
     * @return CompletableFuture resolving to the created EconomySnapshot details
     */
    CompletableFuture<EconomySnapshot> createSnapshotAsync(String label);

    /**
     * Rolls back the entire server economy to the exact state captured in the specified snapshot.
     * Restores balances for all accounts and clears invalid dupe balances in RAM cache and database.
     * @param snapshotId Snapshot ID to restore
     * @return CompletableFuture resolving to true if rollback succeeded
     */
    CompletableFuture<Boolean> restoreServerSnapshotAsync(String snapshotId);

    /**
     * Rolls back a specific player's economy balances across all currencies to the state captured in the snapshot.
     * @param playerUuid Player UUID to rollback
     * @param snapshotId Snapshot ID to restore from
     * @return CompletableFuture resolving to true if rollback succeeded
     */
    CompletableFuture<Boolean> restorePlayerSnapshotAsync(UUID playerUuid, String snapshotId);

    /**
     * Retrieves recent economy snapshots sorted by creation time descending.
     * @param limit Maximum number of snapshots to return
     * @return CompletableFuture resolving to the list of EconomySnapshot entries
     */
    CompletableFuture<List<EconomySnapshot>> getSnapshotsAsync(int limit);

    /**
     * Deletes a snapshot entry and its historical balance data from storage.
     * @param snapshotId Snapshot ID to delete
     * @return CompletableFuture resolving to true if deleted
     */
    CompletableFuture<Boolean> deleteSnapshotAsync(String snapshotId);
}
