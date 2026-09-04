package net.milkbowl.vault.economy.service;

import java.util.UUID;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.events.VaultPreTransactionEvent;
import net.milkbowl.vault.economy.events.VaultTransactionEvent;
import net.milkbowl.vault.economy.events.VaultTransactionEvent.TransactionType;
import net.milkbowl.vault.redis.VaultRedisManager;
import net.milkbowl.vault.util.FoliaScheduler;
import net.milkbowl.vault.util.VaultXVisuals;

public class TransactionExecutorService {

    @FunctionalInterface
    public interface EconomyTransaction {
        EconomyResponse execute() throws Exception;
    }

    private final Plugin plugin;
    private final BalanceCacheManager balanceCacheManager;
    private final boolean debugTransactions;

    public TransactionExecutorService(Plugin plugin, BalanceCacheManager balanceCacheManager, boolean debugTransactions) {
        this.plugin = plugin;
        this.balanceCacheManager = balanceCacheManager;
        this.debugTransactions = debugTransactions;
    }

    public EconomyResponse executeTransaction(
            OfflinePlayer player,
            double amount,
            String currency,
            String type,
            TransactionType eventType,
            Function<OfflinePlayer, Double> balanceSupplier,
            Function<String, Double> currencyBalanceSupplier,
            EconomyTransaction transaction) {

        if (balanceCacheManager.isRateLimited(player)) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Rate limit exceeded");
        }
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player cannot be null");
        }
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid transaction amount");
        }

        double currentBalance = (currency == null) ? balanceSupplier.apply(player) : currencyBalanceSupplier.apply(currency);

        if (VaultPreTransactionEvent.getHandlerList().getRegisteredListeners().length > 0) {
            VaultPreTransactionEvent.TransactionType preType = (eventType == TransactionType.DEPOSIT)
                    ? VaultPreTransactionEvent.TransactionType.DEPOSIT
                    : VaultPreTransactionEvent.TransactionType.WITHDRAW;
            VaultPreTransactionEvent preEvent = new VaultPreTransactionEvent(player, amount, currency, preType, findCallerPlugin());
            Bukkit.getPluginManager().callEvent(preEvent);
            if (preEvent.isCancelled()) {
                return new EconomyResponse(0, currentBalance, EconomyResponse.ResponseType.FAILURE, preEvent.getCancelReason());
            }
        }

        if (net.milkbowl.vault.Vault.getFirewall() != null) {
            if (!net.milkbowl.vault.Vault.getFirewall().checkTransaction(player, amount, type, currentBalance)) {
                return new EconomyResponse(0, currentBalance, EconomyResponse.ResponseType.FAILURE, "Transaction blocked by safety firewall");
            }
        }

        VaultRedisManager redis = VaultRedisManager.getInstance();
        String lockVal = UUID.randomUUID().toString();
        String lockKey = player.getUniqueId().toString() + ":" + (currency == null ? "default" : currency.toLowerCase());
        boolean locked = false;

        if (redis != null) {
            locked = redis.acquireLock(lockKey, lockVal, 3000);
            if (!locked) {
                return new EconomyResponse(0, currentBalance, EconomyResponse.ResponseType.FAILURE, "Account lock active. Try again in a moment.");
            }
        }

        try {
            EconomyResponse response = transaction.execute();
            if (response.transactionSuccess()) {
                balanceCacheManager.updateCache(player, currency, response.balance);
                triggerEventAsync(player, amount, currency, eventType);
                if (net.milkbowl.vault.Vault.getFirewall() != null) {
                    net.milkbowl.vault.Vault.getFirewall().recordTransaction(player, amount);
                }
                if (player.isOnline() && player.getPlayer() != null) {
                    Player onlinePlayer = player.getPlayer();
                    FoliaScheduler.runEntitySync(plugin, onlinePlayer, () ->
                        VaultXVisuals.sendTransactionNotification(
                                onlinePlayer,
                                currency == null ? "default" : currency,
                                amount,
                                eventType == TransactionType.DEPOSIT
                        )
                    );
                }
            }
            return response;
        } catch (Exception e) {
            return new EconomyResponse(0, currentBalance, EconomyResponse.ResponseType.FAILURE, "Transaction error: " + e.getMessage());
        } finally {
            if (redis != null && locked) {
                redis.releaseLock(lockKey, lockVal);
            }
        }
    }

    private void triggerEventAsync(OfflinePlayer player, double amount, String currency, TransactionType type) {
        boolean hasListeners = VaultTransactionEvent.getHandlerList().getRegisteredListeners().length > 0;
        var fm = net.milkbowl.vault.Vault.getFailoverManager();
        boolean hasFailover = fm != null && player != null;

        if (!hasListeners && !hasFailover && !debugTransactions) {
            return;
        }

        if (debugTransactions) {
            Bukkit.getLogger().info("[Vault Debug] Transaction: " + type + " " + amount + " ("
                    + (currency == null ? "default" : currency) + ") for player "
                    + (player != null ? player.getName() : "Unknown"));
        }

        String caller = findCallerPlugin();
        String curr = currency == null ? "default" : currency;
        FoliaScheduler.runAsync(plugin, () -> {
            if (hasListeners) {
                VaultTransactionEvent event = new VaultTransactionEvent(player, null, amount, curr, type, caller, null, -1.0);
                Bukkit.getPluginManager().callEvent(event);
            }
            if (hasFailover) {
                fm.savePlayerTransaction(player.getUniqueId(), type.name(), curr, amount, caller);
            }
        });
    }

    private String findCallerPlugin() {
        if (!debugTransactions) return "VaultX";
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 2; i < stack.length; i++) {
                String className = stack[i].getClassName();
                if (!className.startsWith("net.milkbowl.vault.economy") && !className.startsWith("java.lang")) {
                    int idx = className.indexOf('.', className.indexOf('.') + 1);
                    if (idx > 0) return className.substring(0, idx);
                    return className;
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }
}
