package net.milkbowl.vault.economy;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.milkbowl.vault.economy.events.VaultTransactionEvent;
import net.milkbowl.vault.economy.events.VaultTransactionEvent.TransactionType;
import net.milkbowl.vault.economy.events.VaultPreTransactionEvent;
import net.milkbowl.vault.economy.service.BalanceCacheManager;
import net.milkbowl.vault.economy.service.BankCheckService;
import net.milkbowl.vault.economy.service.BankEconomyService;
import net.milkbowl.vault.economy.service.CurrencyService;
import net.milkbowl.vault.economy.service.ExchangeService;
import net.milkbowl.vault.economy.service.LoanEconomyService;
import net.milkbowl.vault.economy.service.MilestoneService;
import net.milkbowl.vault.economy.service.WealthTaxManager;
import net.milkbowl.vault.economy.service.BoosterService;
import net.milkbowl.vault.economy.service.MailboxService;
import net.milkbowl.vault.economy.service.SubscriptionService;
import net.milkbowl.vault.economy.service.EconomyLockService;
import net.milkbowl.vault.economy.service.AdvancedBankingService;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.redis.VaultRedisManager;
import net.milkbowl.vault.util.FoliaScheduler;
import net.milkbowl.vault.util.StripedLock;
import net.milkbowl.vault.util.UUIDCache;
import net.milkbowl.vault.util.VaultXVisuals;

/**
 * Enterprise-grade high performance decorator wrapper for Vault Economy providers.
 * Implements ultra-fast O(1) in-memory caching for online players, Virtual Threads,
 * Redis cross-server synchronization, and Multi-Currency support.
 */
@SuppressWarnings("deprecation")
public class OptimizedEconomy implements MultiCurrencyEconomy {

    private final Economy delegate;
    private final boolean debugTransactions;
    private final Plugin plugin;
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    // Specialized services
    private final BalanceCacheManager balanceCacheManager;
    private final BankCheckService bankCheckService;
    private final LoanEconomyService loanEconomyService;
    private final MilestoneService milestoneService;
    private final CurrencyService currencyService;
    private final WealthTaxManager wealthTaxManager;
    private final BankEconomyService bankEconomyService;
    private final ExchangeService exchangeService;
    private final BoosterService boosterService;
    private final MailboxService mailboxService;
    private final SubscriptionService subscriptionService;
    private final EconomyLockService economyLockService;
    private final AdvancedBankingService advancedBankingService;
    private final net.milkbowl.vault.economy.service.TransactionExecutorService transactionExecutorService;

    public long getCacheHits() {
        return balanceCacheManager.getCacheHits();
    }

    public long getCacheMisses() {
        return balanceCacheManager.getCacheMisses();
    }

    private final CryptoManager cryptoManager;
    private final AuctionManager auctionManager;
    private final StakingManager stakingManager;
    private final CreditManager creditManager;

    public OptimizedEconomy(Plugin plugin, Economy delegate, boolean useCache,
            boolean debugTransactions, boolean rateLimiterEnabled, int maxTps, int cooldownSeconds,
            boolean nativeBanks) {
        this.plugin = plugin;
        this.debugTransactions = debugTransactions;
        this.delegate = delegate;
        this.cryptoManager = new CryptoManager(plugin);
        this.auctionManager = new AuctionManager(plugin);
        this.stakingManager = new StakingManager(plugin);
        this.creditManager = new CreditManager(plugin);

        // Instantiate specialized services
        this.balanceCacheManager = new BalanceCacheManager(plugin, useCache, rateLimiterEnabled, maxTps, cooldownSeconds);
        this.bankCheckService = new BankCheckService(plugin, asyncExecutor, this);
        this.loanEconomyService = new LoanEconomyService(plugin, asyncExecutor, this);
        this.milestoneService = new MilestoneService(plugin, asyncExecutor);
        this.currencyService = new CurrencyService(plugin);
        this.wealthTaxManager = new WealthTaxManager(plugin);
        this.bankEconomyService = new BankEconomyService(plugin, nativeBanks);
        this.exchangeService = new ExchangeService(plugin);
        this.boosterService = new BoosterService();
        this.mailboxService = new MailboxService(plugin, asyncExecutor);
        this.subscriptionService = new SubscriptionService(asyncExecutor);
        this.economyLockService = new EconomyLockService();
        this.advancedBankingService = new AdvancedBankingService(asyncExecutor);
        this.transactionExecutorService = new net.milkbowl.vault.economy.service.TransactionExecutorService(plugin, balanceCacheManager, debugTransactions);

        preloadBanks();
    }

    public BalanceCacheManager getBalanceCacheManager() {
        return balanceCacheManager;
    }

    public BankCheckService getBankCheckService() {
        return bankCheckService;
    }

    public LoanEconomyService getLoanEconomyService() {
        return loanEconomyService;
    }

    public MilestoneService getMilestoneService() {
        return milestoneService;
    }

    public void updateBankCacheFromRedis(String bankName, double balance) {
        bankEconomyService.updateBankCacheFromRedis(bankName, balance);
    }

    public Economy getDelegate() {
        return delegate;
    }

    public CryptoManager getCryptoManager() {
        return cryptoManager;
    }

    public AuctionManager getAuctionManager() {
        return auctionManager;
    }

    public StakingManager getStakingManager() {
        return stakingManager;
    }

    public CreditManager getCreditManager() {
        return creditManager;
    }

    public CurrencyService getCurrencyService() {
        return currencyService;
    }

    public WealthTaxManager getWealthTaxManager() {
        return wealthTaxManager;
    }

    public BankEconomyService getBankEconomyService() {
        return bankEconomyService;
    }

    public ExchangeService getExchangeService() {
        return exchangeService;
    }

    public BoosterService getBoosterService() {
        return boosterService;
    }

    public MailboxService getMailboxService() {
        return mailboxService;
    }

    public SubscriptionService getSubscriptionService() {
        return subscriptionService;
    }

    public EconomyLockService getEconomyLockService() {
        return economyLockService;
    }

    public AdvancedBankingService getAdvancedBankingService() {
        return advancedBankingService;
    }

    private double getNativeDefaultBalance(OfflinePlayer player) {
        if (player == null) return 0.0;
        return balanceCacheManager.resolveStorageBalance(player.getUniqueId(), "default");
    }

    private EconomyResponse withdrawNativeDefault(OfflinePlayer player, double amount) {
        double current = getNativeDefaultBalance(player);
        if (current < amount) {
            return new EconomyResponse(0, current, EconomyResponse.ResponseType.FAILURE, "Not enough funds");
        }
        double newBal = current - amount;
        saveCustomCurrencyBalance(player, "default", newBal);
        return new EconomyResponse(amount, newBal, EconomyResponse.ResponseType.SUCCESS, "");
    }

    private EconomyResponse depositNativeDefault(OfflinePlayer player, double amount) {
        double current = getNativeDefaultBalance(player);
        double newBal = current + amount;
        saveCustomCurrencyBalance(player, "default", newBal);
        return new EconomyResponse(amount, newBal, EconomyResponse.ResponseType.SUCCESS, "");
    }

    public Map<String, Double> getBankBalances() {
        return bankEconomyService.getBankBalances();
    }

    private OfflinePlayer resolvePlayerFast(String playerName) {
        return UUIDCache.getOfflinePlayerFast(playerName);
    }

    public void preloadBanks() {
        bankEconomyService.preloadBanks();
    }

    /**
     * Invalidate and clear cache for a player when they disconnect or manually.
     */
    public void invalidateCache(OfflinePlayer player) {
        if (player != null) {
            UUID uuid = player.getUniqueId();
            if (cryptoManager != null)
                cryptoManager.invalidatePlayer(uuid);
            if (creditManager != null)
                creditManager.invalidatePlayer(uuid);
            balanceCacheManager.invalidateCache(player);
        }
    }

    public void shutdown() {
        balanceCacheManager.shutdown();

        // Persist all module data to DB before clearing caches
        if (cryptoManager != null) {
            try {
                cryptoManager.saveAllAndClear();
            } catch (Exception e) {
                plugin.getLogger().warning("[VaultX] Error saving crypto wallets on shutdown: " + e.getMessage());
            }
        }
        if (auctionManager != null) {
            try {
                auctionManager.saveAllAndClear();
            } catch (Exception e) {
                plugin.getLogger().warning("[VaultX] Error saving auctions on shutdown: " + e.getMessage());
            }
        }
        if (stakingManager != null) {
            try {
                stakingManager.saveAllAndClear();
            } catch (Exception e) {
                plugin.getLogger().warning("[VaultX] Error saving stakes on shutdown: " + e.getMessage());
            }
        }
        if (creditManager != null) {
            try {
                creditManager.saveAllAndClear();
            } catch (Exception e) {
                plugin.getLogger().warning("[VaultX] Error saving credit accounts on shutdown: " + e.getMessage());
            }
        }

        // Graceful thread pool shutdown
        try {
            asyncExecutor.shutdown();
            if (!asyncExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (Exception ignored) {
            asyncExecutor.shutdownNow();
        }

        bankEconomyService.clear();
        boosterService.clear();
        currencyService.clear();
        milestoneService.clear();
        wealthTaxManager.clear();
    }

    public void onPlayerJoin(Player player) {
        balanceCacheManager.onPlayerJoin(player, () -> warmCache(player.getUniqueId()));
    }

    public double getNativeDefaultBalance(UUID uuid) {
        return balanceCacheManager.getNativeDefaultBalance(uuid);
    }

    public void warmCache(UUID uuid) {
        balanceCacheManager.warmCache(uuid, delegate, getSupportedCurrencies());
    }

    private void updateCache(OfflinePlayer player, String currency, double newBalance) {
        balanceCacheManager.updateCache(player, currency, newBalance);
    }

    public void updateCacheFromRedis(UUID uuid, String currency, double newBalance) {
        balanceCacheManager.updateCacheFromRedis(uuid, currency, newBalance);
    }

    private void saveCustomCurrencyBalance(OfflinePlayer player, String currency, double balance) {
        balanceCacheManager.saveCustomCurrencyBalance(player, currency, balance);
    }

    public void purgePlayerCache(UUID uuid) {
        balanceCacheManager.purgePlayerCache(uuid);
    }

    /* --- INTERCEPTED PERFORMANCE CACHING METHODS --- */

    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null) return 0.0;
        UUID uuid = player.getUniqueId();
        return balanceCacheManager.getOrFetchBalance(uuid, player.isOnline(), player.hasPlayedBefore(), "default",
                () -> (delegate != null) ? delegate.getBalance(player) : getNativeDefaultBalance(player));
    }

    @Override
    public double getBalance(String playerName) {
        if (playerName == null) return 0;
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            return balanceCacheManager.getOrFetchBalance(online.getUniqueId(), true, true, "default",
                    () -> (delegate != null) ? delegate.getBalance(playerName) : getNativeDefaultBalance(online));
        }
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op != null) {
            return balanceCacheManager.getOrFetchBalance(op.getUniqueId(), false, true, "default",
                    () -> (delegate != null) ? delegate.getBalance(playerName) : getNativeDefaultBalance(op));
        }
        return (delegate != null) ? delegate.getBalance(playerName) : 0.0;
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return delegate != null ? delegate.getBalance(player, world) : getBalance(player);
    }

    @Override
    public double getBalance(String playerName, String world) {
        return delegate != null ? delegate.getBalance(playerName, world) : getBalance(playerName);
    }

    private EconomyResponse executeTransaction(OfflinePlayer player, double amount, String currency, String type,
            TransactionType eventType, net.milkbowl.vault.economy.service.TransactionExecutorService.EconomyTransaction transaction) {
        return transactionExecutorService.executeTransaction(player, amount, currency, type, eventType,
                this::getBalance,
                c -> getCurrencyBalance(player, c),
                transaction);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return executeTransaction(player, amount, null, "WITHDRAW", TransactionType.WITHDRAW,
                () -> (delegate != null) ? delegate.withdrawPlayer(player, amount)
                        : withdrawNativeDefault(player, amount));
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op != null)
            return withdrawPlayer(op, amount);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return executeTransaction(player, amount, null, "DEPOSIT", TransactionType.DEPOSIT,
                () -> (delegate != null) ? delegate.depositPlayer(player, amount)
                        : depositNativeDefault(player, amount));
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op != null)
            return depositPlayer(op, amount);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return executeTransaction(player, amount, null, "WITHDRAW", TransactionType.WITHDRAW,
                () -> (delegate != null) ? delegate.withdrawPlayer(player, worldName, amount)
                        : withdrawNativeDefault(player, amount));
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op != null)
            return withdrawPlayer(op, worldName, amount);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return executeTransaction(player, amount, null, "DEPOSIT", TransactionType.DEPOSIT,
                () -> (delegate != null) ? delegate.depositPlayer(player, worldName, amount)
                        : depositNativeDefault(player, amount));
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op != null)
            return depositPlayer(op, worldName, amount);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
    }

    /* --- PURE DELEGATION METHODS --- */

    @Override
    public boolean isEnabled() {
        return delegate != null ? delegate.isEnabled() : true;
    }

    @Override
    public String getName() {
        return delegate != null ? delegate.getName() : "VaultX Native";
    }

    @Override
    public boolean hasBankSupport() {
        return bankEconomyService.hasBankSupport(delegate);
    }

    @Override
    public int fractionalDigits() {
        return delegate != null ? delegate.fractionalDigits() : 2;
    }

    @Override
    public String format(double amount) {
        return currencyService.format(amount, delegate);
    }

    @Override
    public String currencyNamePlural() {
        return delegate != null ? delegate.currencyNamePlural() : "dollars";
    }

    @Override
    public String currencyNameSingular() {
        return delegate != null ? delegate.currencyNameSingular() : "dollar";
    }

    @Override
    public boolean hasAccount(String playerName) {
        return delegate != null ? delegate.hasAccount(playerName) : true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return delegate != null ? delegate.hasAccount(player) : true;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return delegate != null ? delegate.hasAccount(playerName, worldName) : true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return delegate != null ? delegate.hasAccount(player, worldName) : true;
    }

    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return delegate != null ? delegate.has(playerName, worldName, amount) : (getBalance(playerName) >= amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return delegate != null ? delegate.has(player, worldName, amount) : (getBalance(player) >= amount);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return bankEconomyService.createBank(name, player, delegate);
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return bankEconomyService.createBank(name, player, delegate);
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return bankEconomyService.deleteBank(name, delegate);
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return bankEconomyService.bankBalance(name, delegate);
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return bankEconomyService.bankHas(name, amount, delegate);
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return bankEconomyService.bankWithdraw(name, amount, delegate);
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return bankEconomyService.bankDeposit(name, amount, delegate);
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return bankEconomyService.isBankOwner(name, playerName, delegate);
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return bankEconomyService.isBankOwner(name, player, delegate);
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return bankEconomyService.isBankMember(name, playerName, delegate);
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return bankEconomyService.isBankMember(name, player, delegate);
    }

    @Override
    public java.util.List<String> getBanks() {
        return bankEconomyService.getBanks(delegate);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return delegate != null ? delegate.createPlayerAccount(playerName) : true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return delegate != null ? delegate.createPlayerAccount(player) : true;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return delegate != null ? delegate.createPlayerAccount(playerName, worldName) : true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return delegate != null ? delegate.createPlayerAccount(player, worldName) : true;
    }

    /* --- MULTI-CURRENCY API --- */

    @Override
    public java.util.List<String> getSupportedCurrencies() {
        return currencyService.getSupportedCurrencies(delegate);
    }

    @Override
    public double getCurrencyBalance(OfflinePlayer player, String currency) {
        if (player == null || currency == null) return 0.0;
        if (currency.equalsIgnoreCase("default")) return getBalance(player);
        UUID uuid = player.getUniqueId();
        return balanceCacheManager.getOrFetchBalance(uuid, player.isOnline(), player.hasPlayedBefore(), currency, () -> {
            Double delegateBal = currencyService.invokeDelegateGetCurrencyBalance(delegate, player, currency);
            return (delegateBal != null) ? delegateBal : balanceCacheManager.resolveStorageBalance(uuid, currency);
        });
    }

    @Override
    public double getCurrencyBalance(String playerName, String currency) {
        if (playerName == null || currency == null) return 0.0;
        if (currency.equalsIgnoreCase("default")) return getBalance(playerName);
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            UUID uuid = online.getUniqueId();
            return balanceCacheManager.getOrFetchBalance(uuid, true, true, currency, () -> {
                Double delegateBal = currencyService.invokeDelegateGetCurrencyBalance(delegate, playerName, currency);
                return (delegateBal != null) ? delegateBal : balanceCacheManager.resolveStorageBalance(uuid, currency);
            });
        }
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op != null) {
            UUID uuid = op.getUniqueId();
            return balanceCacheManager.getOrFetchBalance(uuid, false, true, currency, () -> {
                Double delegateBal = currencyService.invokeDelegateGetCurrencyBalance(delegate, playerName, currency);
                return (delegateBal != null) ? delegateBal : balanceCacheManager.resolveStorageBalance(uuid, currency);
            });
        }
        Double delegateBal = currencyService.invokeDelegateGetCurrencyBalance(delegate, playerName, currency);
        return (delegateBal != null) ? delegateBal : 0.0;
    }

    @Override
    public EconomyResponse withdrawCurrencyPlayer(OfflinePlayer player, String currency, double amount) {
        return executeTransaction(player, amount, currency, "WITHDRAW_" + currency, TransactionType.WITHDRAW, () -> {
            EconomyResponse delegateRes = currencyService.invokeDelegateWithdrawCurrency(delegate, player, currency, amount);
            if (delegateRes != null) {
                if (!delegateRes.transactionSuccess() && exchangeService.isAutoConvertEnabled()) {
                    return exchangeService.handleAutoConvertWithdraw(player, currency, amount,
                            this::getBalance,
                            this::withdrawPlayer,
                            c -> getCurrencyBalance(player, c));
                }
                return delegateRes;
            } else {
                if (currency.equalsIgnoreCase("default")) {
                    return (delegate != null) ? delegate.withdrawPlayer(player, amount)
                            : withdrawNativeDefault(player, amount);
                } else {
                    double bal = getCurrencyBalance(player, currency);
                    if (bal >= amount) {
                        bal -= amount;
                        saveCustomCurrencyBalance(player, currency, bal);
                        return new EconomyResponse(amount, bal, EconomyResponse.ResponseType.SUCCESS, "");
                    } else if (exchangeService.isAutoConvertEnabled()) {
                        return exchangeService.handleAutoConvertWithdraw(player, currency, amount,
                                this::getBalance,
                                this::withdrawPlayer,
                                c -> getCurrencyBalance(player, c));
                    } else {
                        return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Not enough funds");
                    }
                }
            }
        });
    }

    @Override
    public EconomyResponse withdrawCurrencyPlayer(String playerName, String currency, double amount) {
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op != null)
            return withdrawCurrencyPlayer(op, currency, amount);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
    }

    @Override
    public EconomyResponse depositCurrencyPlayer(OfflinePlayer player, String currency, double amount) {
        return executeTransaction(player, amount, currency, "DEPOSIT_" + currency, TransactionType.DEPOSIT, () -> {
            EconomyResponse delegateRes = currencyService.invokeDelegateDepositCurrency(delegate, player, currency, amount);
            if (delegateRes != null) {
                return delegateRes;
            } else {
                if (currency.equalsIgnoreCase("default")) {
                    return (delegate != null) ? delegate.depositPlayer(player, amount)
                            : depositNativeDefault(player, amount);
                } else {
                    double bal = getCurrencyBalance(player, currency) + amount;
                    saveCustomCurrencyBalance(player, currency, bal);
                    return new EconomyResponse(amount, bal, EconomyResponse.ResponseType.SUCCESS, "");
                }
            }
        });
    }

    @Override
    public EconomyResponse depositCurrencyPlayer(String playerName, String currency, double amount) {
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op != null)
            return depositCurrencyPlayer(op, currency, amount);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
    }

    @Override
    public boolean hasCurrencyAccount(OfflinePlayer player, String currency) {
        return currencyService.hasCurrencyAccount(delegate, player, currency);
    }

    @Override
    public boolean hasCurrency(OfflinePlayer player, String currency, double amount) {
        return getCurrencyBalance(player, currency) >= amount;
    }
}
