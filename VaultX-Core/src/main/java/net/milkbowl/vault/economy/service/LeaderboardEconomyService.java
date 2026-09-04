package net.milkbowl.vault.economy.service;

import net.milkbowl.vault.economy.VaultLeaderboardAPI;
import net.milkbowl.vault.redis.LocalFailoverManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Specialized service for leaderboard queries and rich list player rank calculations.
 */
public class LeaderboardEconomyService implements VaultLeaderboardAPI {

    private final ExecutorService asyncExecutor;

    public LeaderboardEconomyService(ExecutorService asyncExecutor) {
        this.asyncExecutor = asyncExecutor;
    }

    private LocalFailoverManager failover() {
        return net.milkbowl.vault.Vault.getFailoverManager();
    }

    @Override
    public CompletableFuture<List<LeaderboardEntry>> getTopBalancesAsync(String currency, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<LeaderboardEntry> entries = new ArrayList<>();
            LocalFailoverManager fm = failover();
            if (fm != null) {
                Map<UUID, Double> topMap = fm.getTopBalances(currency == null ? "default" : currency, limit);
                int rank = 1;
                for (Map.Entry<UUID, Double> entry : topMap.entrySet()) {
                    OfflinePlayer p = Bukkit.getOfflinePlayer(entry.getKey());
                    entries.add(new LeaderboardEntry(
                            entry.getKey(),
                            p != null && p.getName() != null ? p.getName() : "Unknown",
                            entry.getValue(),
                            rank++
                    ));
                }
            }
            return entries;
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Integer> getPlayerRankAsync(OfflinePlayer player, String currency) {
        return CompletableFuture.supplyAsync(() -> {
            LocalFailoverManager fm = failover();
            if (player == null || fm == null) return -1;
            return fm.getPlayerRank(player.getUniqueId(), currency == null ? "default" : currency);
        }, asyncExecutor);
    }
}
