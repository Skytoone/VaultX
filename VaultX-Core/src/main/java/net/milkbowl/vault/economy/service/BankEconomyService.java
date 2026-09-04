package net.milkbowl.vault.economy.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.events.VaultBankTransactionEvent;
import net.milkbowl.vault.economy.events.VaultBankTransactionEvent.BankTransactionType;
import net.milkbowl.vault.redis.VaultPostgresManager;
import net.milkbowl.vault.redis.VaultRedisManager;
import net.milkbowl.vault.util.FoliaScheduler;

/**
 * Manages native and delegated bank accounts, bank balance caches, and bank operations.
 */
public class BankEconomyService {

    private final Plugin plugin;
    private final boolean nativeBanks;
    private final Map<String, Double> bankBalances = new ConcurrentHashMap<>();

    public BankEconomyService(Plugin plugin, boolean nativeBanks) {
        this.plugin = plugin;
        this.nativeBanks = nativeBanks;
    }

    public boolean hasBankSupport(Economy delegate) {
        if (nativeBanks)
            return true;
        return delegate != null ? delegate.hasBankSupport() : false;
    }

    public Map<String, Double> getBankBalances() {
        return bankBalances;
    }

    public void updateBankCacheFromRedis(String bankName, double balance) {
        bankBalances.put(bankName.toLowerCase(), balance);
    }

    public void preloadBanks() {
        if (!nativeBanks)
            return;
        FoliaScheduler.runAsync(plugin, () -> {
            try {
                // 1. Load from local database (SQLite/MySQL)
                Map<String, Double> localBanks = Vault.getFailoverManager().loadAllBanks();
                if (localBanks != null) {
                    for (Map.Entry<String, Double> entry : localBanks.entrySet()) {
                        bankBalances.put(entry.getKey().toLowerCase(), entry.getValue());
                    }
                }

                // 2. If Redis is enabled and online, load from Redis
                VaultRedisManager redis = VaultRedisManager.getInstance();
                if (redis != null && redis.isOnline()) {
                    for (String bankName : bankBalances.keySet()) {
                        double redisBal = redis.getBankBalance(bankName);
                        bankBalances.put(bankName.toLowerCase(), redisBal);
                    }
                }
                plugin.getLogger().info("[VaultX] Preloaded " + bankBalances.size() + " banks into memory cache.");
            } catch (Exception e) {
                plugin.getLogger().warning("[VaultX] Failed to preload banks: " + e.getMessage());
            }
        });
    }

    public double getBankBalanceNative(String name) {
        String key = name.toLowerCase();
        if (bankBalances.containsKey(key))
            return bankBalances.get(key);
        double bal = 0.0;
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            bal = redis.getBankBalance(name);
        } else {
            bal = Vault.getFailoverManager().getBankBalance(name);
        }
        bankBalances.put(key, bal);
        return bal;
    }

    public EconomyResponse createBank(String name, String player, Economy delegate) {
        if (!nativeBanks && delegate != null)
            return delegate.createBank(name, player);
        if (bankBalances.containsKey(name.toLowerCase()))
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank exists");
        bankBalances.put(name.toLowerCase(), 0.0);
        VaultRedisManager redis = VaultRedisManager.getInstance();
        VaultPostgresManager postgres = VaultPostgresManager.getInstance();
        if (redis != null && redis.isOnline()) {
            redis.setBankBalance(name, 0.0);
        } else if (postgres != null) {
            postgres.setBankBalance(name, 0.0);
            Vault.getFailoverManager().saveBankBalance(name, 0.0);
        } else {
            Vault.getFailoverManager().saveBankBalance(name, 0.0);
        }
        Bukkit.getPluginManager()
                .callEvent(new VaultBankTransactionEvent(name, player, 0.0, BankTransactionType.CREATE_BANK, 0.0));
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.SUCCESS, "");
    }

    public EconomyResponse createBank(String name, OfflinePlayer player, Economy delegate) {
        return createBank(name, player != null ? player.getName() : "", delegate);
    }

    public EconomyResponse deleteBank(String name, Economy delegate) {
        if (!nativeBanks && delegate != null)
            return delegate.deleteBank(name);
        bankBalances.remove(name.toLowerCase());
        VaultRedisManager redis = VaultRedisManager.getInstance();
        VaultPostgresManager postgres = VaultPostgresManager.getInstance();
        if (redis != null && redis.isOnline()) {
            redis.setBankBalance(name, 0.0);
        } else if (postgres != null) {
            postgres.setBankBalance(name, 0.0);
            Vault.getFailoverManager().deleteBankAccount(name);
        } else {
            Vault.getFailoverManager().deleteBankAccount(name);
        }
        Bukkit.getPluginManager().callEvent(
                new VaultBankTransactionEvent(name, (String) null, 0.0, BankTransactionType.DELETE_BANK, 0.0));
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.SUCCESS, "");
    }

    public EconomyResponse bankBalance(String name, Economy delegate) {
        if (!nativeBanks && delegate != null)
            return delegate.bankBalance(name);
        return new EconomyResponse(0, getBankBalanceNative(name), EconomyResponse.ResponseType.SUCCESS, "");
    }

    public EconomyResponse bankHas(String name, double amount, Economy delegate) {
        if (!nativeBanks && delegate != null)
            return delegate.bankHas(name, amount);
        double bal = getBankBalanceNative(name);
        if (bal >= amount)
            return new EconomyResponse(0, bal, EconomyResponse.ResponseType.SUCCESS, "");
        return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Not enough funds");
    }

    public EconomyResponse bankWithdraw(String name, double amount, Economy delegate) {
        if (!nativeBanks && delegate != null)
            return delegate.bankWithdraw(name, amount);
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            return new EconomyResponse(0, getBankBalanceNative(name), EconomyResponse.ResponseType.FAILURE, "Invalid transaction amount");
        }
        double bal = getBankBalanceNative(name);
        if (bal >= amount) {
            bal -= amount;
            bankBalances.put(name.toLowerCase(), bal);
            VaultRedisManager redis = VaultRedisManager.getInstance();
            VaultPostgresManager postgres = VaultPostgresManager.getInstance();
            if (redis != null && redis.isOnline()) {
                redis.setBankBalance(name, bal);
            } else if (postgres != null) {
                postgres.setBankBalance(name, bal);
                Vault.getFailoverManager().saveBankBalance(name, bal);
            } else {
                Vault.getFailoverManager().saveBankBalance(name, bal);
            }
            Bukkit.getPluginManager().callEvent(
                    new VaultBankTransactionEvent(name, (String) null, amount, BankTransactionType.WITHDRAW, bal));
            return new EconomyResponse(amount, bal, EconomyResponse.ResponseType.SUCCESS, "");
        }
        return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Not enough funds");
    }

    public EconomyResponse bankDeposit(String name, double amount, Economy delegate) {
        if (!nativeBanks && delegate != null)
            return delegate.bankDeposit(name, amount);
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            return new EconomyResponse(0, getBankBalanceNative(name), EconomyResponse.ResponseType.FAILURE, "Invalid transaction amount");
        }
        double bal = getBankBalanceNative(name) + amount;
        bankBalances.put(name.toLowerCase(), bal);
        VaultRedisManager redis = VaultRedisManager.getInstance();
        VaultPostgresManager postgres = VaultPostgresManager.getInstance();
        if (redis != null && redis.isOnline()) {
            redis.setBankBalance(name, bal);
        } else if (postgres != null) {
            postgres.setBankBalance(name, bal);
            Vault.getFailoverManager().saveBankBalance(name, bal);
        } else {
            Vault.getFailoverManager().saveBankBalance(name, bal);
        }
        Bukkit.getPluginManager().callEvent(
                new VaultBankTransactionEvent(name, (String) null, amount, BankTransactionType.DEPOSIT, bal));
        return new EconomyResponse(amount, bal, EconomyResponse.ResponseType.SUCCESS, "");
    }

    public EconomyResponse isBankOwner(String name, String playerName, Economy delegate) {
        if (!nativeBanks && delegate != null)
            return delegate.isBankOwner(name, playerName);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "VaultX Banks are shared");
    }

    public EconomyResponse isBankOwner(String name, OfflinePlayer player, Economy delegate) {
        return isBankOwner(name, "", delegate);
    }

    public EconomyResponse isBankMember(String name, String playerName, Economy delegate) {
        if (!nativeBanks && delegate != null)
            return delegate.isBankMember(name, playerName);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "VaultX Banks are shared");
    }

    public EconomyResponse isBankMember(String name, OfflinePlayer player, Economy delegate) {
        return isBankMember(name, "", delegate);
    }

    public List<String> getBanks(Economy delegate) {
        if (!nativeBanks && delegate != null)
            return delegate.getBanks();
        return new ArrayList<>(bankBalances.keySet());
    }

    public void clear() {
        bankBalances.clear();
    }
}
