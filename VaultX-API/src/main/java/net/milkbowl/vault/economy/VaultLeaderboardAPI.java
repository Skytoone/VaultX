package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * High-performance Leaderboard API for querying rich list balances asynchronously.
 */
public interface VaultLeaderboardAPI {

    record LeaderboardEntry(UUID playerUuid, String playerName, double balance, int rank) {}

    /**
     * Queries the top balances asynchronously for a given currency.
     * @param currency Currency ID (e.g. "default", "gems", "tokens")
     * @param limit Maximum entries to return (e.g. 10)
     * @return Future containing ranked list of LeaderboardEntry
     */
    CompletableFuture<List<LeaderboardEntry>> getTopBalancesAsync(String currency, int limit);

    /**
     * Gets a player's leaderboard position asynchronously.
     * @param player OfflinePlayer
     * @param currency Currency ID
     * @return Future containing the player's 1-based rank (-1 if unranked)
     */
    CompletableFuture<Integer> getPlayerRankAsync(OfflinePlayer player, String currency);
}
