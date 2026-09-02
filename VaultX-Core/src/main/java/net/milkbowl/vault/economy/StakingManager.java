package net.milkbowl.vault.economy;

import net.milkbowl.vault.redis.LocalFailoverManager;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;

public class StakingManager {

    private final Plugin plugin;
    private final Map<String, VaultStakingAPI.StakeDeposit> activeStakes = new ConcurrentHashMap<>();

    private final java.util.Set<String> claimingStakes = ConcurrentHashMap.newKeySet();

    public StakingManager(Plugin plugin) {
        this.plugin = plugin;
        loadAllFromDb();
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public void loadAllFromDb() {
        LocalFailoverManager failover = LocalFailoverManager.getInstance();
        if (failover != null) {
            Map<String, VaultStakingAPI.StakeDeposit> loaded = failover.loadAllStakes();
            activeStakes.putAll(loaded);
        }
    }

    public CompletableFuture<EconomyResponse> createStakeAsync(
            OfflinePlayer player, String currency, double amount, int durationDays,
            BiFunction<OfflinePlayer, String, Double> getBalFunc,
            AuctionManager.TriFunction<OfflinePlayer, String, Double, EconomyResponse> withdrawFunc,
            ExecutorService executor) {
        CompletableFuture<EconomyResponse> future = new CompletableFuture<>();
        if (player == null || amount <= 0 || durationDays <= 0 || withdrawFunc == null) {
            future.complete(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid staking parameters"));
            return future;
        }
        String curr = currency == null ? "default" : currency;
        org.bukkit.entity.Player onlinePlayer = player.isOnline() ? player.getPlayer() : null;

        Runnable action = () -> {
            try {
                EconomyResponse res = withdrawFunc.apply(player, curr, amount);
                if (!res.transactionSuccess()) {
                    future.complete(res);
                    return;
                }

                String stakeId = "stake_" + UUID.randomUUID().toString().substring(0, 8);
                long now = System.currentTimeMillis();
                long lockMs = durationDays * 86400000L;
                double rate = 0.05 * (durationDays / 30.0 + 1.0);

                VaultStakingAPI.StakeDeposit deposit = new VaultStakingAPI.StakeDeposit(
                        stakeId, player.getUniqueId(), curr, amount, rate, now, lockMs, false, false
                );
                activeStakes.put(stakeId, deposit);

                LocalFailoverManager failover = LocalFailoverManager.getInstance();
                if (failover != null && plugin != null) {
                    net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> failover.saveStake(deposit));
                } else if (failover != null) {
                    failover.saveStake(deposit);
                }
                double currentBal = getBalFunc != null ? getBalFunc.apply(player, curr) : 0.0;
                future.complete(new EconomyResponse(amount, currentBal, EconomyResponse.ResponseType.SUCCESS, "Staked " + amount + " " + curr));
            } catch (Exception e) {
                future.complete(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage()));
            }
        };

        if (onlinePlayer != null && plugin != null) {
            net.milkbowl.vault.util.FoliaScheduler.runEntitySync(plugin, onlinePlayer, action);
        } else if (executor != null) {
            executor.execute(action);
        } else {
            CompletableFuture.runAsync(action);
        }
        return future;
    }

    public CompletableFuture<EconomyResponse> claimStakeAsync(
            OfflinePlayer player, String depositId,
            AuctionManager.TriFunction<OfflinePlayer, String, Double, EconomyResponse> depositFunc,
            ExecutorService executor) {
        CompletableFuture<EconomyResponse> future = new CompletableFuture<>();
        if (player == null || depositId == null || depositFunc == null) {
            future.complete(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid arguments"));
            return future;
        }

        if (!claimingStakes.add(depositId)) {
            future.complete(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Stake deposit is currently being processed"));
            return future;
        }

        VaultStakingAPI.StakeDeposit deposit = activeStakes.get(depositId);
        if (deposit == null || !deposit.playerUuid().equals(player.getUniqueId())) {
            claimingStakes.remove(depositId);
            future.complete(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Stake deposit not found"));
            return future;
        }
        if (deposit.isClaimed()) {
            claimingStakes.remove(depositId);
            future.complete(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Stake deposit already claimed"));
            return future;
        }
        long now = System.currentTimeMillis();
        if (now < deposit.stakedAtMs() + deposit.lockPeriodMs()) {
            claimingStakes.remove(depositId);
            future.complete(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Stake deposit is still locked"));
            return future;
        }

        org.bukkit.entity.Player onlinePlayer = player.isOnline() ? player.getPlayer() : null;
        Runnable action = () -> {
            try {
                double totalPayout = deposit.principal() * (1.0 + deposit.interestRate());
                EconomyResponse res = depositFunc.apply(player, deposit.currency(), totalPayout);
                if (res.transactionSuccess()) {
                    VaultStakingAPI.StakeDeposit updated = new VaultStakingAPI.StakeDeposit(
                            deposit.depositId(), deposit.playerUuid(), deposit.currency(), deposit.principal(),
                            deposit.interestRate(), deposit.stakedAtMs(), deposit.lockPeriodMs(), true, true
                    );
                    activeStakes.put(depositId, updated);

                    LocalFailoverManager failover = LocalFailoverManager.getInstance();
                    if (failover != null && plugin != null) {
                        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> failover.saveStake(updated));
                    } else if (failover != null) {
                        failover.saveStake(updated);
                    }
                }
                future.complete(res);
            } catch (Exception e) {
                future.complete(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage()));
            } finally {
                claimingStakes.remove(depositId);
            }
        };

        if (onlinePlayer != null && plugin != null) {
            net.milkbowl.vault.util.FoliaScheduler.runEntitySync(plugin, onlinePlayer, action);
        } else if (executor != null) {
            executor.execute(action);
        } else {
            CompletableFuture.runAsync(action);
        }
        return future;
    }

    public CompletableFuture<List<VaultStakingAPI.StakeDeposit>> getActiveStakesAsync(OfflinePlayer player, ExecutorService executor) {
        java.util.function.Supplier<List<VaultStakingAPI.StakeDeposit>> supplier = () -> {
            if (player == null) return Collections.emptyList();
            return activeStakes.values().stream()
                    .filter(s -> s.playerUuid().equals(player.getUniqueId()) && !s.isClaimed())
                    .toList();
        };
        return executor != null ? CompletableFuture.supplyAsync(supplier, executor) : CompletableFuture.supplyAsync(supplier);
    }

    public void saveAllAndClear() {
        LocalFailoverManager failover = LocalFailoverManager.getInstance();
        if (failover != null) {
            for (VaultStakingAPI.StakeDeposit deposit : activeStakes.values()) {
                failover.saveStake(deposit);
            }
        }
        activeStakes.clear();
    }
}
