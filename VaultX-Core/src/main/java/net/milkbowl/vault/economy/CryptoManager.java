package net.milkbowl.vault.economy;

import net.milkbowl.vault.economy.events.VaultCryptoMineEvent;
import net.milkbowl.vault.redis.LocalFailoverManager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

public class CryptoManager implements VaultCryptoAPI {

    private final Plugin plugin;
    private final Map<UUID, Map<String, Double>> cryptoWallets = new ConcurrentHashMap<>();

    public CryptoManager(Plugin plugin) {
        this.plugin = plugin;
        loadAllFromDb();
    }

    public void loadAllFromDb() {
        LocalFailoverManager failover = LocalFailoverManager.getInstance();
        if (failover != null) {
            Map<UUID, Map<String, Double>> loaded = failover.loadAllCryptoWallets();
            for (Map.Entry<UUID, Map<String, Double>> entry : loaded.entrySet()) {
                cryptoWallets.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
            }
        }
    }

    private UUID resolveUuid(String input) {
        if (input == null) return null;
        if (input.startsWith("vx_") && input.length() >= 11) {
            String prefix = input.substring(3);
            for (UUID uuid : cryptoWallets.keySet()) {
                if (uuid.toString().startsWith(prefix)) return uuid;
            }
            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            if (failover != null) {
                Map<UUID, Map<String, Double>> loaded = failover.loadAllCryptoWallets();
                for (Map.Entry<UUID, Map<String, Double>> entry : loaded.entrySet()) {
                    cryptoWallets.putIfAbsent(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
                    if (entry.getKey().toString().startsWith(prefix)) {
                        return entry.getKey();
                    }
                }
            }
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op != null && op.getUniqueId() != null && op.getUniqueId().toString().startsWith(prefix)) {
                    return op.getUniqueId();
                }
            }
        }
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException e) {
            OfflinePlayer op = net.milkbowl.vault.util.UUIDCache.getOfflinePlayerFast(input);
            return op != null ? op.getUniqueId() : null;
        }
    }

    private Map<String, Double> getOrLoadPlayerWallets(UUID uuid) {
        return cryptoWallets.computeIfAbsent(uuid, k -> {
            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            Map<String, Double> loaded = failover != null ? failover.loadCryptoWalletForPlayer(k) : null;
            return new ConcurrentHashMap<>(loaded != null ? loaded : new java.util.HashMap<>());
        });
    }

    public CompletableFuture<VaultCryptoAPI.CryptoWallet> getWalletAsync(OfflinePlayer player, String cryptoName, ExecutorService executor) {
        java.util.function.Supplier<VaultCryptoAPI.CryptoWallet> supplier = () -> {
            if (player == null) {
                return new VaultCryptoAPI.CryptoWallet("vx_00000000", cryptoName, 0.0);
            }
            UUID uuid = player.getUniqueId();
            double bal = getOrLoadPlayerWallets(uuid).getOrDefault(cryptoName, 0.0);
            return new VaultCryptoAPI.CryptoWallet("vx_" + uuid.toString().substring(0, 8), cryptoName, bal);
        };
        return executor != null ? CompletableFuture.supplyAsync(supplier, executor) : CompletableFuture.supplyAsync(supplier);
    }

    public CompletableFuture<EconomyResponse> mineTokensAsync(OfflinePlayer player, String cryptoName, double amount, ExecutorService executor) {
        if (!plugin.getConfig().getBoolean("crypto.enabled", true) || !plugin.getConfig().getBoolean("crypto.mining-enabled", true)) {
            return CompletableFuture.completedFuture(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Crypto mining is disabled in config.yml"));
        }
        if (player == null || Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            return CompletableFuture.completedFuture(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid player or amount"));
        }
        java.util.function.Supplier<EconomyResponse> supplier = () -> {
            UUID uuid = player.getUniqueId();
            Map<String, Double> playerWallets = getOrLoadPlayerWallets(uuid);
            double newBal = playerWallets.merge(cryptoName, amount, Double::sum);

            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            if (failover != null) {
                failover.saveCryptoWallet(uuid, cryptoName, newBal);
            }
            double difficulty = plugin.getConfig().getDouble("crypto.difficulty", 1.0);
            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                Bukkit.getPluginManager().callEvent(new VaultCryptoMineEvent(player, cryptoName, amount, difficulty));
            });
            return new EconomyResponse(amount, newBal, EconomyResponse.ResponseType.SUCCESS, "Mined " + amount + " " + cryptoName);
        };
        return executor != null ? CompletableFuture.supplyAsync(supplier, executor) : CompletableFuture.supplyAsync(supplier);
    }

    public CompletableFuture<Boolean> transferCryptoAsync(String fromAddress, String toAddress, String cryptoName, double amount, ExecutorService executor) {
        if (!plugin.getConfig().getBoolean("crypto.enabled", true) || Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        java.util.function.Supplier<Boolean> supplier = () -> {
            UUID fromUuid = resolveUuid(fromAddress);
            UUID toUuid = resolveUuid(toAddress);
            if (fromUuid == null || toUuid == null || fromUuid.equals(toUuid)) return false;

            Map<String, Double> fromWallet = getOrLoadPlayerWallets(fromUuid);
            Map<String, Double> toWallet = getOrLoadPlayerWallets(toUuid);

            double fromBal;
            double toBal;
            double feePercent = plugin.getConfig().getDouble("crypto.transfer-fee-percent", 0.1);
            double fee = (feePercent > 0) ? (amount * (feePercent / 100.0)) : 0.0;
            double netTransfer = Math.max(0.0, amount - fee);

            Map<String, Double> firstLock = fromUuid.compareTo(toUuid) < 0 ? fromWallet : toWallet;
            Map<String, Double> secondLock = fromUuid.compareTo(toUuid) < 0 ? toWallet : fromWallet;

            synchronized (firstLock) {
                synchronized (secondLock) {
                    fromBal = fromWallet.getOrDefault(cryptoName, 0.0);
                    if (fromBal < amount) return false;
                    fromBal -= amount;
                    fromWallet.put(cryptoName, fromBal);

                    toBal = toWallet.getOrDefault(cryptoName, 0.0) + netTransfer;
                    toWallet.put(cryptoName, toBal);
                }
            }

            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            if (failover != null) {
                failover.saveCryptoWallet(fromUuid, cryptoName, fromBal);
                failover.saveCryptoWallet(toUuid, cryptoName, toBal);
            }
            return true;
        };
        return executor != null ? CompletableFuture.supplyAsync(supplier, executor) : CompletableFuture.supplyAsync(supplier);
    }

    public void savePlayerWallet(UUID uuid) {
        Map<String, Double> playerWallets = cryptoWallets.get(uuid);
        if (playerWallets != null) {
            LocalFailoverManager failover = LocalFailoverManager.getInstance();
            if (failover != null) {
                for (Map.Entry<String, Double> e : playerWallets.entrySet()) {
                    failover.saveCryptoWallet(uuid, e.getKey(), e.getValue());
                }
            }
        }
    }

    public void invalidatePlayer(UUID uuid) {
        savePlayerWallet(uuid);
        cryptoWallets.remove(uuid);
    }

    public void saveAllAndClear() {
        LocalFailoverManager failover = LocalFailoverManager.getInstance();
        if (failover != null) {
            for (Map.Entry<UUID, Map<String, Double>> entry : cryptoWallets.entrySet()) {
                UUID uuid = entry.getKey();
                for (Map.Entry<String, Double> sub : entry.getValue().entrySet()) {
                    failover.saveCryptoWallet(uuid, sub.getKey(), sub.getValue());
                }
            }
        }
        cryptoWallets.clear();
    }

    @Override
    public CompletableFuture<VaultCryptoAPI.CryptoWallet> getWalletAsync(OfflinePlayer player, String cryptoName) {
        return getWalletAsync(player, cryptoName, null);
    }

    @Override
    public CompletableFuture<EconomyResponse> mineTokensAsync(OfflinePlayer player, String cryptoName, double amount) {
        return mineTokensAsync(player, cryptoName, amount, null);
    }

    @Override
    public CompletableFuture<Boolean> transferCryptoAsync(String fromAddress, String toAddress, String cryptoName, double amount) {
        return transferCryptoAsync(fromAddress, toAddress, cryptoName, amount, null);
    }
}
