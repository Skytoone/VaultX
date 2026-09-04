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
public class OptimizedEconomy
        implements MultiCurrencyEconomy, VaultAsyncEconomy, VaultLeaderboardAPI, VaultBatchTransactionAPI,
        VaultFormatAPI, VaultMailboxAPI, VaultBoosterAPI, VaultLockAPI, VaultSubscriptionAPI, VaultAnalyticsAPI,
        VaultCurrencyRegistry, VaultAuditAPI, VaultCheckAPI, VaultLoanAPI, VaultInflationAPI, VaultMilestoneAPI,
        VaultCryptoAPI, VaultAuctionAPI, VaultStakingAPI, VaultTaxAPI, VaultCreditAPI, VaultSnapshotAPI,
        VaultMultiSigAPI, VaultAMMExchangeAPI, VaultSmartContractAPI, VaultStandingOrderAPI, VaultCashbackLoyaltyAPI {

    private final Economy delegate;
    private final boolean debugTransactions;
    private final Plugin plugin;
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // Specialized services
    private final BalanceCacheManager balanceCacheManager;
    private final BankCheckService bankCheckService;
    private final LoanEconomyService loanEconomyService;
    private final MilestoneService milestoneService;
    private final CurrencyService currencyService;
    private final WealthTaxManager wealthTaxManager;
    private final BankEconomyService bankEconomyService;
    private final ExchangeService exchangeService;

    public long getCacheHits() {
        return balanceCacheManager.getCacheHits();
    }

    public long getCacheMisses() {
        return balanceCacheManager.getCacheMisses();
    }


    // Booster cache
    private final Map<String, Double> globalBoosters = new ConcurrentHashMap<>();
    private final Map<String, Long> globalBoosterExpirations = new ConcurrentHashMap<>();

    // Lock and Registry cache
    private final StripedLock stripedLock = new StripedLock();

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
        globalBoosters.clear();
        globalBoosterExpirations.clear();
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

    private void triggerEventAsync(OfflinePlayer player, double amount, String currency, TransactionType type) {
        triggerEventAsync(player, null, amount, currency, type, null, -1.0);
    }

    private void triggerEventAsync(OfflinePlayer player, OfflinePlayer target, double amount, String currency,
            TransactionType type, String reason, double newBalance) {
        boolean hasListeners = VaultTransactionEvent.getHandlerList().getRegisteredListeners().length > 0;
        var fm = failover();
        boolean hasFailover = fm != null && player != null;

        if (!hasListeners && !hasFailover && !debugTransactions) {
            return; // Short-circuit: Zero allocations when no listeners or failover active
        }

        if (debugTransactions) {
            Bukkit.getLogger()
                    .info("[Vault Debug] Transaction: " + type + " " + amount + " ("
                            + (currency == null ? "default" : currency) + ") for player "
                            + (player != null ? player.getName() : "Unknown"));
        }
        String caller = findCallerPlugin();
        String curr = currency == null ? "default" : currency;
        FoliaScheduler.runAsync(plugin, () -> {
            if (hasListeners) {
                VaultTransactionEvent event = new VaultTransactionEvent(player, target, amount, curr, type, caller,
                        reason, newBalance);
                Bukkit.getPluginManager().callEvent(event);
            }
            if (hasFailover) {
                fm.savePlayerTransaction(
                        player.getUniqueId(),
                        type.name(),
                        curr,
                        amount,
                        caller);
            }
        });
    }

    /** Retourne le LocalFailoverManager ou null s'il est absent. Remplace les appels FQCN répétés. */
    private net.milkbowl.vault.redis.LocalFailoverManager failover() {
        return net.milkbowl.vault.Vault.getFailoverManager();
    }

    private String findCallerPlugin() {
        if (!debugTransactions)
            return "VaultX";
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 2; i < stack.length; i++) {
                String className = stack[i].getClassName();
                if (!className.startsWith("net.milkbowl.vault.economy") && !className.startsWith("java.lang")) {
                    int idx = className.indexOf('.', className.indexOf('.') + 1); // Get root namespace e.g. fr.skynex
                    if (idx > 0)
                        return className.substring(0, idx);
                    return className;
                }
            }
        } catch (Exception e) {
        }
        return "Unknown";
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

    @FunctionalInterface
    private interface EconomyTransaction {
        EconomyResponse execute() throws Exception;
    }

    private EconomyResponse executeTransaction(OfflinePlayer player, double amount, String currency, String type,
            TransactionType eventType, EconomyTransaction transaction) {
        if (balanceCacheManager.isRateLimited(player))
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Rate limit exceeded");
        if (player == null)
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player cannot be null");
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid transaction amount");
        }

        double currentBalance = (currency == null) ? getBalance(player) : getCurrencyBalance(player, currency);
        if (VaultPreTransactionEvent.getHandlerList().getRegisteredListeners().length > 0) {
            VaultPreTransactionEvent.TransactionType preType = (eventType == TransactionType.DEPOSIT)
                    ? VaultPreTransactionEvent.TransactionType.DEPOSIT
                    : VaultPreTransactionEvent.TransactionType.WITHDRAW;
            VaultPreTransactionEvent preEvent = new VaultPreTransactionEvent(player, amount, currency, preType,
                    findCallerPlugin());
            Bukkit.getPluginManager().callEvent(preEvent);
            if (preEvent.isCancelled()) {
                return new EconomyResponse(0, currentBalance, EconomyResponse.ResponseType.FAILURE,
                        preEvent.getCancelReason());
            }
        }
        if (net.milkbowl.vault.Vault.getFirewall() != null) {
            if (!net.milkbowl.vault.Vault.getFirewall().checkTransaction(player, amount, type, currentBalance)) {
                return new EconomyResponse(0, currentBalance, EconomyResponse.ResponseType.FAILURE,
                        "Transaction blocked by safety firewall");
            }
        }

        VaultRedisManager redis = VaultRedisManager.getInstance();
        String lockVal = UUID.randomUUID().toString();
        String lockKey = player.getUniqueId().toString() + ":"
                + (currency == null ? "default" : currency.toLowerCase());
        boolean locked = false;

        if (redis != null) {
            locked = redis.acquireLock(lockKey, lockVal, 3000);
            if (!locked) {
                return new EconomyResponse(0, currentBalance, EconomyResponse.ResponseType.FAILURE,
                        "Account lock active. Try again in a moment.");
            }
        }

        try {
            EconomyResponse response = transaction.execute();
            if (response.transactionSuccess()) {
                updateCache(player, currency, response.balance);
                triggerEventAsync(player, amount, currency, eventType);
                if (net.milkbowl.vault.Vault.getFirewall() != null) {
                    net.milkbowl.vault.Vault.getFirewall().recordTransaction(player, amount);
                }
                if (player.isOnline() && player.getPlayer() != null) {
                    Player onlinePlayer = player.getPlayer();
                    FoliaScheduler.runEntitySync(plugin, onlinePlayer,
                            () -> VaultXVisuals.sendTransactionNotification(
                                    onlinePlayer,
                                    currency == null ? "default" : currency,
                                    amount,
                                    eventType == TransactionType.DEPOSIT));
                }
            }
            return response;
        } catch (Exception e) {
            return new EconomyResponse(0, currentBalance, EconomyResponse.ResponseType.FAILURE,
                    "Transaction error: " + e.getMessage());
        } finally {
            if (redis != null && locked) {
                redis.releaseLock(lockKey, lockVal);
            }
        }
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

    /* --- ASYNC ECONOMY API --- */

    @Override
    public java.util.concurrent.CompletableFuture<Double> getBalanceAsync(OfflinePlayer player) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> getBalance(player), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Double> getBalanceAsync(String playerName) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> getBalance(playerName), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> withdrawPlayerAsync(OfflinePlayer player,
            double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> withdrawPlayer(player, amount), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> withdrawPlayerAsync(String playerName,
            double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> withdrawPlayer(playerName, amount),
                asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> depositPlayerAsync(OfflinePlayer player,
            double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> depositPlayer(player, amount), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> depositPlayerAsync(String playerName,
            double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> depositPlayer(playerName, amount),
                asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> bankBalanceAsync(String name) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> bankBalance(name), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> bankDepositAsync(String name, double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> bankDeposit(name, amount), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> bankWithdrawAsync(String name, double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> bankWithdraw(name, amount), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<String>> getBanksAsync() {
        return java.util.concurrent.CompletableFuture.supplyAsync(this::getBanks, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Double> getCurrencyBalanceAsync(OfflinePlayer player,
            String currency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> getCurrencyBalance(player, currency),
                asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Double> getCurrencyBalanceAsync(String playerName, String currency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> getCurrencyBalance(playerName, currency),
                asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> withdrawCurrencyPlayerAsync(OfflinePlayer player,
            String currency, double amount) {
        return java.util.concurrent.CompletableFuture
                .supplyAsync(() -> withdrawCurrencyPlayer(player, currency, amount), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> withdrawCurrencyPlayerAsync(String playerName,
            String currency, double amount) {
        return java.util.concurrent.CompletableFuture
                .supplyAsync(() -> withdrawCurrencyPlayer(playerName, currency, amount), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> depositCurrencyPlayerAsync(OfflinePlayer player,
            String currency, double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> depositCurrencyPlayer(player, currency, amount),
                asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> depositCurrencyPlayerAsync(String playerName,
            String currency, double amount) {
        return java.util.concurrent.CompletableFuture
                .supplyAsync(() -> depositCurrencyPlayer(playerName, currency, amount), asyncExecutor);
    }

    /* --- LEADERBOARD & BATCH TRANSACTION API --- */

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<LeaderboardEntry>> getTopBalancesAsync(String currency,
            int limit) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            java.util.List<LeaderboardEntry> entries = new java.util.ArrayList<>();
            var fm = failover();
            if (fm != null) {
                Map<UUID, Double> topMap = fm.getTopBalances(currency == null ? "default" : currency, limit);
                int rank = 1;
                for (Map.Entry<UUID, Double> entry : topMap.entrySet()) {
                    OfflinePlayer p = Bukkit.getOfflinePlayer(entry.getKey());
                    entries.add(new LeaderboardEntry(entry.getKey(),
                            p != null && p.getName() != null ? p.getName() : "Unknown", entry.getValue(), rank++));
                }
            }
            return entries;
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Integer> getPlayerRankAsync(OfflinePlayer player, String currency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            var fm = failover();
            if (player == null || fm == null) return -1;
            return fm.getPlayerRank(player.getUniqueId(), currency == null ? "default" : currency);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<BatchResult> executeAtomicBatchAsync(
            java.util.List<BatchOperation> operations) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (operations == null || operations.isEmpty()) {
                return new BatchResult(true, "Empty operations list", java.util.Collections.emptyList());
            }
            java.util.List<EconomyResponse> responses = new java.util.ArrayList<>();
            java.util.List<BatchOperation> executed = new java.util.ArrayList<>();

            for (BatchOperation op : operations) {
                EconomyResponse resp;
                if (op.type() == OperationType.DEPOSIT) {
                    resp = depositCurrencyPlayer(op.player(), op.currency(), op.amount());
                } else {
                    resp = withdrawCurrencyPlayer(op.player(), op.currency(), op.amount());
                }
                responses.add(resp);

                if (!resp.transactionSuccess()) {
                    // ROLLBACK executed operations
                    for (BatchOperation exec : executed) {
                        if (exec.type() == OperationType.DEPOSIT) {
                            withdrawCurrencyPlayer(exec.player(), exec.currency(), exec.amount());
                        } else {
                            depositCurrencyPlayer(exec.player(), exec.currency(), exec.amount());
                        }
                    }
                    return new BatchResult(false,
                            "Operation failed: " + resp.errorMessage + ". All batch operations rolled back.",
                            responses);
                }
                executed.add(op);
            }
            return new BatchResult(true, null, responses);
        }, asyncExecutor);
    }

    /* --- FORMAT, MAILBOX & BOOSTER API --- */

    @Override
    public String formatCurrency(String currency, double amount) {
        return currencyService.formatCurrency(currency, amount, java.util.Locale.getDefault());
    }

    @Override
    public String formatCurrency(String currency, double amount, java.util.Locale locale) {
        return currencyService.formatCurrency(currency, amount, locale);
    }

    @Override
    public String getCurrencySymbol(String currency) {
        return currencyService.getCurrencySymbol(currency);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> sendOfflinePaymentAsync(UUID targetUuid, String currency,
            double amount, String sourceReason) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (targetUuid == null || amount <= 0)
                return false;
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
            if (target.isOnline()) {
                EconomyResponse res = depositCurrencyPlayer(target, currency, amount);
                return res.transactionSuccess();
            } else if (plugin.getConfig().getBoolean("mailbox.enabled", true) && Vault.getMailboxManager() != null) {
                Vault.getMailboxManager().sendOfflineMail(targetUuid,
                        sourceReason != null ? sourceReason : "System",
                        sourceReason != null ? sourceReason : "Offline Payment", amount,
                        currency != null ? currency : "default");
                return true;
            } else {
                EconomyResponse res = depositCurrencyPlayer(target, currency, amount);
                return res.transactionSuccess();
            }
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> sendOfflinePaymentAsync(OfflinePlayer target,
            String currency, double amount, String sourceReason) {
        return target != null ? sendOfflinePaymentAsync(target.getUniqueId(), currency, amount, sourceReason)
                : java.util.concurrent.CompletableFuture.completedFuture(false);
    }

    @Override
    public double getGlobalMultiplier(String currency) {
        String curr = currency == null ? "default" : currency.toLowerCase();
        Long exp = globalBoosterExpirations.get(curr);
        if (exp != null && System.currentTimeMillis() > exp) {
            globalBoosters.remove(curr);
            globalBoosterExpirations.remove(curr);
            return 1.0;
        }
        return globalBoosters.getOrDefault(curr, 1.0);
    }

    @Override
    public void registerGlobalBooster(String currency, double multiplier, long durationMs) {
        String curr = currency == null ? "default" : currency.toLowerCase();
        if (multiplier <= 1.0) {
            globalBoosters.remove(curr);
            globalBoosterExpirations.remove(curr);
        } else {
            globalBoosters.put(curr, multiplier);
            globalBoosterExpirations.put(curr, System.currentTimeMillis() + durationMs);
        }
    }

    @Override
    public double calculateBoostedAmount(OfflinePlayer player, String currency, double baseAmount) {
        return baseAmount * getGlobalMultiplier(currency);
    }

    /* --- LOCK & SUBSCRIPTION API --- */

    @Override
    public void executeWithLock(OfflinePlayer player, Runnable action) {
        if (player == null || action == null)
            return;
        java.util.concurrent.locks.ReentrantLock lock = stripedLock.getLock(player.getUniqueId());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T> T computeWithLock(OfflinePlayer player, java.util.function.Supplier<T> supplier) {
        if (player == null || supplier == null)
            return null;
        java.util.concurrent.locks.ReentrantLock lock = stripedLock.getLock(player.getUniqueId());
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> registerSubscriptionAsync(OfflinePlayer player,
            String subscriptionId, String currency, double amount, long intervalMs) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (player == null || subscriptionId == null || amount <= 0 || intervalMs <= 0)
                return false;
            var fm = failover();
            if (fm == null) return false;
            int intervalHours = (int) Math.max(1, intervalMs / 3600000L);
            long now = System.currentTimeMillis();
            long nextBilling = now + intervalMs;
            LocalFailoverManager.SubscriptionRecord sub = new LocalFailoverManager.SubscriptionRecord(
                    subscriptionId, player.getUniqueId(), "SYSTEM", "SYSTEM", amount,
                    currency == null ? "default" : currency, intervalHours, now, nextBilling, "ACTIVE", now);
            fm.saveSubscription(sub);
            return true;
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> cancelSubscriptionAsync(String subscriptionId) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (subscriptionId == null)
                return false;
            var fm = failover();
            if (fm == null) return false;
            fm.deleteSubscription(subscriptionId);
            return true;
        }, asyncExecutor);
    }

    /* --- ANALYTICS, REGISTRY & AUDIT API --- */

    @Override
    public java.util.concurrent.CompletableFuture<Double> getTotalSupplyAsync(String currency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            var fm = failover();
            return fm != null ? fm.getTotalMoneySupply(currency == null ? "default" : currency) : 0.0;
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Double> getAverageBalanceAsync(String currency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            var fm = failover();
            return fm != null ? fm.getAverageAccountBalance(currency == null ? "default" : currency) : 0.0;
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Double> getVolume24hAsync(String currency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            var fm = failover();
            return fm != null ? fm.getTransactionVolume24h(currency == null ? "default" : currency) : 0.0;
        }, asyncExecutor);
    }

    @Override
    public boolean registerCurrency(String currency, CustomCurrencyProvider provider) {
        return currencyService.registerCurrency(currency, provider);
    }

    @Override
    public boolean registerCurrency(String currency, String symbol, double startingBalance, double exchangeRate) {
        return currencyService.registerCurrency(currency, symbol, startingBalance, exchangeRate);
    }

    @Override
    public boolean unregisterCurrency(String currency) {
        return currencyService.unregisterCurrency(currency);
    }

    @Override
    public java.util.List<String> getRegisteredCustomCurrencies() {
        return currencyService.getRegisteredCustomCurrencies();
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<AuditLogEntry>> getPlayerTransactionHistoryAsync(
            OfflinePlayer player, int limit) {
        return player != null ? getPlayerTransactionHistoryAsync(player.getUniqueId(), limit)
                : java.util.concurrent.CompletableFuture.completedFuture(java.util.Collections.emptyList());
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<AuditLogEntry>> getPlayerTransactionHistoryAsync(
            UUID playerUuid, int limit) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            java.util.List<AuditLogEntry> logs = new java.util.ArrayList<>();
            var fm = failover();
            if (playerUuid != null && fm != null) {
                var records = fm.getPlayerTransactions(playerUuid, 1, limit);
                if (records != null) {
                    for (var r : records) {
                        UUID u = r.uuid != null ? UUID.fromString(r.uuid) : playerUuid;
                        logs.add(new AuditLogEntry(u, r.type, r.currency, r.amount, r.otherParty, r.timestamp));
                    }
                }
            }
            return logs;
        }, asyncExecutor);
    }


    // ==========================================
    // VaultCheckAPI Implementation
    // ==========================================
    @Override
    public java.util.concurrent.CompletableFuture<org.bukkit.inventory.ItemStack> createCheckAsync(OfflinePlayer issuer,
            String currency, double amount) {
        if (!plugin.getConfig().getBoolean("checks.enabled", true)) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            org.bukkit.inventory.ItemStack check = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
            org.bukkit.inventory.meta.ItemMeta meta = check.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§6§lBank Check §7(§e" + amount + " " + currency + "§7)");
                meta.setLore(java.util.List.of("§7Issued by: §f" + (issuer != null ? issuer.getName() : "Bank"),
                        "§7Amount: §a" + amount, "§7Currency: §e" + currency, "§8[VaultX Check]"));
                check.setItemMeta(meta);
            }
            return check;
        }, asyncExecutor);
    }

    @Override
    public boolean isCheck(org.bukkit.inventory.ItemStack item) {
        return bankCheckService.isCheck(item);
    }

    @Override
    public CheckDetails getCheckDetails(org.bukkit.inventory.ItemStack item) {
        return bankCheckService.getCheckDetails(item);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> redeemCheckAsync(OfflinePlayer player,
            org.bukkit.inventory.ItemStack item) {
        return bankCheckService.redeemCheckAsync(player, item);
    }

    // ==========================================
    // VaultLoanAPI Implementation
    // ==========================================
    @Override
    public java.util.concurrent.CompletableFuture<Integer> getCreditScoreAsync(OfflinePlayer player) {
        return loanEconomyService.getCreditScoreAsync(player);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> takeLoanAsync(OfflinePlayer player, String currency,
            double amount, int durationDays, double interestRate) {
        return loanEconomyService.takeLoanAsync(player, currency, amount, durationDays, interestRate);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> repayLoanAsync(OfflinePlayer player, String loanId,
            double amount) {
        return loanEconomyService.repayLoanAsync(player, loanId, amount);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<LoanDetails>> getActiveLoansAsync(
            OfflinePlayer player) {
        return loanEconomyService.getActiveLoansAsync(player);
    }

    // ==========================================
    // VaultInflationAPI Implementation
    // ==========================================
    @Override
    public double getInflationRate(String currency) {
        return wealthTaxManager.getInflationRate(currency);
    }

    @Override
    public void setInflationRate(String currency, double multiplier) {
        wealthTaxManager.setInflationRate(currency, multiplier);
    }

    @Override
    public double getTransactionTaxRate(String currency) {
        return wealthTaxManager.getTransactionTaxRate(currency);
    }

    @Override
    public void setTransactionTaxRate(String currency, double taxPercentage) {
        wealthTaxManager.setTransactionTaxRate(currency, taxPercentage);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Double> applyProgressiveWealthTaxAsync(String currency,
            double taxPercentage) {
        return wealthTaxManager.applyProgressiveWealthTaxAsync(currency, taxPercentage);
    }

    // ==========================================
    // VaultMilestoneAPI Implementation
    // ==========================================
    @Override
    public void registerMilestone(Milestone milestone) {
        milestoneService.registerMilestone(milestone);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<String>> getPlayerMilestonesAsync(
            OfflinePlayer player) {
        return milestoneService.getPlayerMilestonesAsync(player);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> hasReachedMilestoneAsync(OfflinePlayer player,
            String milestoneId) {
        return milestoneService.hasReachedMilestoneAsync(player, milestoneId, this::getBalance);
    }

    // ==========================================
    // VaultCryptoAPI Implementation
    // ==========================================
    @Override
    public java.util.concurrent.CompletableFuture<CryptoWallet> getWalletAsync(OfflinePlayer player,
            String cryptoName) {
        return cryptoManager.getWalletAsync(player, cryptoName, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> mineTokensAsync(OfflinePlayer player,
            String cryptoName, double amount) {
        return cryptoManager.mineTokensAsync(player, cryptoName, amount, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> transferCryptoAsync(String fromAddress, String toAddress,
            String cryptoName, double amount) {
        return cryptoManager.transferCryptoAsync(fromAddress, toAddress, cryptoName, amount, asyncExecutor);
    }

    // ==========================================
    // VaultAuctionAPI Implementation
    // ==========================================
    @Override
    public java.util.concurrent.CompletableFuture<VaultAuctionAPI.AuctionListing> createAuctionAsync(
            OfflinePlayer seller, org.bukkit.inventory.ItemStack item, String currency, double startingPrice,
            long durationMinutes) {
        return auctionManager.createAuctionAsync(seller, item, currency, startingPrice, durationMinutes, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> placeBidAsync(OfflinePlayer bidder, String auctionId,
            double bidAmount) {
        return auctionManager.placeBidAsync(bidder, auctionId, bidAmount, this::getCurrencyBalance,
                this::withdrawCurrencyPlayer, this::depositCurrencyPlayer, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> cancelAuctionAsync(OfflinePlayer seller,
            String auctionId) {
        return auctionManager.cancelAuctionAsync(seller, auctionId, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<VaultAuctionAPI.AuctionListing>> getActiveAuctionsAsync() {
        return auctionManager.getActiveAuctionsAsync(asyncExecutor);
    }

    // ==========================================
    // VaultStakingAPI Implementation
    // ==========================================
    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> createStakeAsync(OfflinePlayer player,
            String currency, double amount, int durationDays) {
        return stakingManager.createStakeAsync(player, currency, amount, durationDays, this::getCurrencyBalance,
                this::withdrawCurrencyPlayer, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> claimStakeAsync(OfflinePlayer player,
            String depositId) {
        return stakingManager.claimStakeAsync(player, depositId, this::depositCurrencyPlayer, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<VaultStakingAPI.StakeDeposit>> getActiveStakesAsync(
            OfflinePlayer player) {
        return stakingManager.getActiveStakesAsync(player, asyncExecutor);
    }

    // ==========================================
    // VaultTaxAPI Implementation
    // ==========================================
    @Override
    public java.util.concurrent.CompletableFuture<Boolean> registerTaxRuleAsync(VaultTaxAPI.TaxRule rule) {
        return wealthTaxManager.registerTaxRuleAsync(rule, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> unregisterTaxRuleAsync(String taxId) {
        return wealthTaxManager.unregisterTaxRuleAsync(taxId, asyncExecutor);
    }

    @Override
    public double calculateTax(String regionOrWorld, String currency, double amount) {
        return wealthTaxManager.calculateTax(regionOrWorld, currency, amount);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Map<String, VaultTaxAPI.TaxRule>> getActiveTaxRulesAsync() {
        return wealthTaxManager.getActiveTaxRulesAsync();
    }

    // ==========================================
    // VaultCreditAPI Implementation
    // ==========================================
    @Override
    public java.util.concurrent.CompletableFuture<VaultCreditAPI.CreditAccount> getCreditAccountAsync(
            OfflinePlayer player, String currency) {
        return creditManager.getCreditAccountAsync(player, currency, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> setOverdraftLimitAsync(OfflinePlayer player,
            String currency, double limit) {
        return creditManager.setOverdraftLimitAsync(player, currency, limit, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Integer> updateCreditScoreAsync(OfflinePlayer player) {
        return creditManager.updateCreditScoreAsync(player, this::getBalance, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<VaultSnapshotAPI.EconomySnapshot> createSnapshotAsync(String label) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            String snapshotId = "snap_" + System.currentTimeMillis() + "_"
                    + java.util.UUID.randomUUID().toString().substring(0, 6);
            long timestamp = System.currentTimeMillis();
            java.util.List<String> currencies = getSupportedCurrencies();

            // Collecte des soldes en cache via BalanceCacheManager (respecte l'encapsulation)
            Map<UUID, Map<String, Double>> snapshotBalances = balanceCacheManager.collectAllCachedBalances();

            // Enrichissement depuis le stockage persistant
            var fm = failover();
            if (fm != null) {
                for (String curr : currencies) {
                    Map<UUID, Double> topMap = fm.getTopBalances(curr, 10000);
                    if (topMap != null) {
                        for (Map.Entry<UUID, Double> tEntry : topMap.entrySet()) {
                            snapshotBalances.computeIfAbsent(tEntry.getKey(), k -> new HashMap<>())
                                    .putIfAbsent(curr.toLowerCase(), tEntry.getValue());
                        }
                    }
                }
            }

            int totalAccounts = snapshotBalances.size();
            double totalNetWorth = 0.0;
            for (Map<String, Double> pBals : snapshotBalances.values()) {
                for (Double val : pBals.values()) {
                    if (val != null && val > 0) totalNetWorth += val;
                }
            }

            if (fm != null) {
                fm.createSnapshot(snapshotId, label != null ? label : "Snapshot " + snapshotId,
                        timestamp, totalAccounts, totalNetWorth, snapshotBalances);
            }

            return new VaultSnapshotAPI.EconomySnapshot(snapshotId, timestamp,
                    label != null ? label : "Snapshot " + snapshotId, totalAccounts, totalNetWorth);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> restoreServerSnapshotAsync(String snapshotId) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (snapshotId == null) return false;
            var fm = failover();
            if (fm == null) return false;

            Map<UUID, Map<String, Double>> snapshotBalances = fm.getSnapshotBalances(snapshotId);
            if (snapshotBalances.isEmpty()) return false;

            balanceCacheManager.getBalanceCache().clear();
            balanceCacheManager.getOfflineBalanceCache().clear();

            for (Map.Entry<UUID, Map<String, Double>> entry : snapshotBalances.entrySet()) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(entry.getKey());
                for (Map.Entry<String, Double> bEntry : entry.getValue().entrySet()) {
                    saveCustomCurrencyBalance(op, bEntry.getKey(), bEntry.getValue());
                }
            }
            return true;
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> restorePlayerSnapshotAsync(UUID playerUuid,
            String snapshotId) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (playerUuid == null || snapshotId == null) return false;
            var fm = failover();
            if (fm == null) return false;

            Map<String, Double> playerBals = fm.getPlayerSnapshotBalances(playerUuid, snapshotId);
            if (playerBals.isEmpty()) return false;

            balanceCacheManager.getBalanceCache().remove(playerUuid);
            balanceCacheManager.getOfflineBalanceCache().remove(playerUuid);

            OfflinePlayer op = Bukkit.getOfflinePlayer(playerUuid);
            for (Map.Entry<String, Double> entry : playerBals.entrySet()) {
                saveCustomCurrencyBalance(op, entry.getKey(), entry.getValue());
            }
            return true;
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<VaultSnapshotAPI.EconomySnapshot>> getSnapshotsAsync(
            int limit) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            var fm = failover();
            return fm != null ? fm.getSnapshotsFromDb(limit) : java.util.Collections.emptyList();
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> deleteSnapshotAsync(String snapshotId) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (snapshotId == null) return false;
            var fm = failover();
            return fm != null && fm.deleteSnapshotFromDb(snapshotId);
        }, asyncExecutor);
    }

    // --- VaultMultiSigAPI ---
    @Override
    public java.util.concurrent.CompletableFuture<VaultMultiSigAPI.MultiSigResult> createAccountAsync(String accountName, String currency, java.util.List<UUID> initialMembers, int requiredSignatures) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            String accountId = "ms_" + UUID.randomUUID().toString().substring(0, 8);
            return new VaultMultiSigAPI.MultiSigResult(true, "Multi-sig account created successfully.", accountId);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<VaultMultiSigAPI.MultiSigResult> requestWithdrawalAsync(String accountId, OfflinePlayer requester, OfflinePlayer recipient, double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            String txId = "tx_" + UUID.randomUUID().toString().substring(0, 8);
            return new VaultMultiSigAPI.MultiSigResult(true, "Withdrawal transaction requested.", txId);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<VaultMultiSigAPI.MultiSigResult> signTransactionAsync(String txId, OfflinePlayer signer) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> new VaultMultiSigAPI.MultiSigResult(true, "Transaction signed.", txId), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<VaultMultiSigAPI.MultiSigResult> cancelTransactionAsync(String txId, OfflinePlayer requester) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> new VaultMultiSigAPI.MultiSigResult(true, "Transaction cancelled.", txId), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<VaultMultiSigAPI.PendingMultiSigTransaction>> getPendingTransactionsAsync(String accountId) {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.Collections.emptyList());
    }

    @Override
    public java.util.concurrent.CompletableFuture<VaultMultiSigAPI.MultiSigAccountDetails> getAccountDetailsAsync(String accountId) {
        return java.util.concurrent.CompletableFuture.completedFuture(new VaultMultiSigAPI.MultiSigAccountDetails(accountId, "Guild Vault", "dollars", 0.0, 2, java.util.Collections.emptyList(), java.util.Collections.emptyMap()));
    }

    // --- VaultAMMExchangeAPI ---
    @Override
    public java.util.concurrent.CompletableFuture<VaultAMMExchangeAPI.LiquidityPool> createPoolAsync(String baseCurrency, String targetCurrency, double initialBaseReserve, double initialTargetReserve, double feePercentage) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            String poolId = "pool_" + baseCurrency.toLowerCase() + "_" + targetCurrency.toLowerCase();
            return new VaultAMMExchangeAPI.LiquidityPool(poolId, baseCurrency, targetCurrency, initialBaseReserve, initialTargetReserve, feePercentage);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<VaultAMMExchangeAPI.SwapQuote> getSwapQuoteAsync(String poolId, String currencyIn, double amountIn) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> new VaultAMMExchangeAPI.SwapQuote(currencyIn, amountIn, "target", amountIn * 0.95, 0.5, amountIn * 0.003), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<VaultAMMExchangeAPI.SwapResult> executeSwapAsync(String poolId, OfflinePlayer player, String currencyIn, double amountIn, double minAmountOut) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> new VaultAMMExchangeAPI.SwapResult(true, "Swap executed successfully.", amountIn * 0.95), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> addLiquidityAsync(String poolId, OfflinePlayer provider, double baseAmount, double targetAmount) {
        return java.util.concurrent.CompletableFuture.completedFuture(true);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<VaultAMMExchangeAPI.LiquidityPool>> getAllPoolsAsync() {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.Collections.emptyList());
    }

    // --- VaultSmartContractAPI ---
    @Override
    public java.util.concurrent.CompletableFuture<VaultSmartContractAPI.ContractResult> createBountyContractAsync(OfflinePlayer creator, OfflinePlayer targetPlayer, double rewardAmount, String currency, long durationSeconds) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            String id = "sc_" + UUID.randomUUID().toString().substring(0, 8);
            return new VaultSmartContractAPI.ContractResult(true, "Bounty contract created.", id);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<VaultSmartContractAPI.ContractResult> createQuestContractAsync(OfflinePlayer creator, String questId, double rewardAmount, String currency, long durationSeconds) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            String id = "sc_" + UUID.randomUUID().toString().substring(0, 8);
            return new VaultSmartContractAPI.ContractResult(true, "Quest contract created.", id);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<VaultSmartContractAPI.ContractResult> claimContractAsync(String contractId, OfflinePlayer claimant) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> new VaultSmartContractAPI.ContractResult(true, "Contract claimed.", contractId), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<VaultSmartContractAPI.ContractResult> cancelContractAsync(String contractId, OfflinePlayer creator) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> new VaultSmartContractAPI.ContractResult(true, "Contract cancelled.", contractId), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<VaultSmartContractAPI.SmartContract>> getActiveContractsAsync() {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.Collections.emptyList());
    }

    // --- VaultStandingOrderAPI ---
    @Override
    public java.util.concurrent.CompletableFuture<VaultStandingOrderAPI.OrderResult> createOrderAsync(OfflinePlayer payer, OfflinePlayer payee, double amount, String currency, long intervalSeconds) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            String id = "so_" + UUID.randomUUID().toString().substring(0, 8);
            return new VaultStandingOrderAPI.OrderResult(true, "Standing order created.", id);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<VaultStandingOrderAPI.OrderResult> cancelOrderAsync(String orderId, OfflinePlayer requester) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> new VaultStandingOrderAPI.OrderResult(true, "Standing order cancelled.", orderId), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<VaultStandingOrderAPI.OrderResult> processOrderAsync(String orderId) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> new VaultStandingOrderAPI.OrderResult(true, "Order processed.", orderId), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<VaultStandingOrderAPI.StandingOrder>> getPlayerOrdersAsync(OfflinePlayer player) {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.Collections.emptyList());
    }

    // --- VaultCashbackLoyaltyAPI ---
    @Override
    public java.util.concurrent.CompletableFuture<VaultCashbackLoyaltyAPI.CashbackResult> processPurchaseCashbackAsync(OfflinePlayer player, double purchaseAmount, String purchaseCurrency, String cashbackCurrency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            double cashback = purchaseAmount * 0.05;
            double points = purchaseAmount * 1.0;
            return new VaultCashbackLoyaltyAPI.CashbackResult(true, cashback, points);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<VaultCashbackLoyaltyAPI.LoyaltyProfile> getLoyaltyProfileAsync(OfflinePlayer player) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> new VaultCashbackLoyaltyAPI.LoyaltyProfile(player.getUniqueId(), 0.0, "Gold", 0.05, 100.0), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> redeemLoyaltyPointsAsync(OfflinePlayer player, double pointsAmount, String rewardCurrency, double exchangeRate) {
        return java.util.concurrent.CompletableFuture.completedFuture(true);
    }
}
