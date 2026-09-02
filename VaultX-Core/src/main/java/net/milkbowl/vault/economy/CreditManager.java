package net.milkbowl.vault.economy;

import net.milkbowl.vault.redis.LocalFailoverManager;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

public class CreditManager {

    private final Plugin plugin;
    private final Map<UUID, Map<String, VaultCreditAPI.CreditAccount>> creditAccounts = new ConcurrentHashMap<>();

    public CreditManager(Plugin plugin) {
        this.plugin = plugin;
        loadAllFromDb();
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public void loadAllFromDb() {
        LocalFailoverManager failover = LocalFailoverManager.getInstance();
        if (failover != null) {
            Map<UUID, Map<String, VaultCreditAPI.CreditAccount>> loaded = failover.loadAllCreditAccounts();
            for (Map.Entry<UUID, Map<String, VaultCreditAPI.CreditAccount>> entry : loaded.entrySet()) {
                creditAccounts.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
            }
        }
    }

    private Map<String, VaultCreditAPI.CreditAccount> getOrLoadPlayerCreditAccounts(UUID uuid) {
        return creditAccounts.computeIfAbsent(uuid, k -> {
            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            Map<String, VaultCreditAPI.CreditAccount> loaded = failover != null ? failover.loadCreditAccountsForPlayer(k) : null;
            return new ConcurrentHashMap<>(loaded != null ? loaded : new java.util.HashMap<>());
        });
    }

    public CompletableFuture<VaultCreditAPI.CreditAccount> getCreditAccountAsync(OfflinePlayer player, String currency, ExecutorService executor) {
        java.util.function.Supplier<VaultCreditAPI.CreditAccount> supplier = () -> {
            if (player == null) {
                return new VaultCreditAPI.CreditAccount(new UUID(0L, 0L), "default", 0.0, 0.0, 300, true);
            }
            UUID uuid = player.getUniqueId();
            String curr = currency == null ? "default" : currency;
            Map<String, VaultCreditAPI.CreditAccount> playerMap = getOrLoadPlayerCreditAccounts(uuid);
            VaultCreditAPI.CreditAccount acc = playerMap.computeIfAbsent(curr, c -> new VaultCreditAPI.CreditAccount(uuid, c, 500.0, 0.0, 700, false));

            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            if (failover != null) {
                failover.saveCreditAccount(acc);
            }
            return acc;
        };
        return executor != null ? CompletableFuture.supplyAsync(supplier, executor) : CompletableFuture.supplyAsync(supplier);
    }

    public CompletableFuture<EconomyResponse> setOverdraftLimitAsync(OfflinePlayer player, String currency, double limit, ExecutorService executor) {
        java.util.function.Supplier<EconomyResponse> supplier = () -> {
            if (player == null || Double.isNaN(limit) || Double.isInfinite(limit) || limit < 0) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid arguments");
            }
            String curr = currency == null ? "default" : currency;
            Map<String, VaultCreditAPI.CreditAccount> accMap = getOrLoadPlayerCreditAccounts(player.getUniqueId());
            VaultCreditAPI.CreditAccount existing = accMap.getOrDefault(curr, new VaultCreditAPI.CreditAccount(player.getUniqueId(), curr, 500.0, 0.0, 700, false));
            VaultCreditAPI.CreditAccount updated = new VaultCreditAPI.CreditAccount(existing.playerUuid(), curr, limit, existing.currentUsedCredit(), existing.creditScore(), existing.isFrozen());
            accMap.put(curr, updated);

            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            if (failover != null) {
                failover.saveCreditAccount(updated);
            }
            return new EconomyResponse(limit, limit, EconomyResponse.ResponseType.SUCCESS, "Overdraft limit updated to " + limit);
        };
        return executor != null ? CompletableFuture.supplyAsync(supplier, executor) : CompletableFuture.supplyAsync(supplier);
    }

    public CompletableFuture<Integer> updateCreditScoreAsync(OfflinePlayer player, Function<OfflinePlayer, Double> getBalFunc, ExecutorService executor) {
        java.util.function.Supplier<Integer> supplier = () -> {
            if (player == null) return 300;
            double bal = getBalFunc != null ? getBalFunc.apply(player) : 0.0;
            int score = (int) Math.min(850, Math.max(300, 600 + (bal / 1000.0)));

            UUID uuid = player.getUniqueId();
            Map<String, VaultCreditAPI.CreditAccount> playerMap = getOrLoadPlayerCreditAccounts(uuid);
            VaultCreditAPI.CreditAccount existing = playerMap.getOrDefault("default", new VaultCreditAPI.CreditAccount(uuid, "default", 500.0, 0.0, 700, false));
            VaultCreditAPI.CreditAccount updated = new VaultCreditAPI.CreditAccount(existing.playerUuid(), existing.currency(), existing.overdraftLimit(), existing.currentUsedCredit(), score, existing.isFrozen());
            playerMap.put(existing.currency(), updated);

            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            if (failover != null) {
                failover.saveCreditAccount(updated);
            }
            return score;
        };
        return executor != null ? CompletableFuture.supplyAsync(supplier, executor) : CompletableFuture.supplyAsync(supplier);
    }

    public void invalidatePlayer(UUID uuid) {
        Map<String, VaultCreditAPI.CreditAccount> accMap = creditAccounts.get(uuid);
        if (accMap != null) {
            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            if (failover != null) {
                for (VaultCreditAPI.CreditAccount acc : accMap.values()) {
                    failover.saveCreditAccount(acc);
                }
            }
            creditAccounts.remove(uuid);
        }
    }

    public void saveAllAndClear() {
        LocalFailoverManager failover = LocalFailoverManager.getInstance();
        if (failover != null) {
            for (Map.Entry<UUID, Map<String, VaultCreditAPI.CreditAccount>> entry : creditAccounts.entrySet()) {
                for (VaultCreditAPI.CreditAccount acc : entry.getValue().values()) {
                    failover.saveCreditAccount(acc);
                }
            }
        }
        creditAccounts.clear();
    }
}
