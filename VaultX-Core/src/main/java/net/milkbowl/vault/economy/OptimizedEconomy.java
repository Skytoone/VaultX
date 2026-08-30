package net.milkbowl.vault.economy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.milkbowl.vault.economy.events.VaultTransactionEvent;
import net.milkbowl.vault.economy.events.VaultTransactionEvent.TransactionType;
import net.milkbowl.vault.economy.events.VaultPreTransactionEvent;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import net.milkbowl.vault.redis.VaultRedisManager;
import net.milkbowl.vault.util.StripedLock;

/**
 * Enterprise-grade high performance decorator wrapper for Vault Economy
 * providers.
 * Implements ultra-fast O(1) in-memory caching for online players, Virtual
 * Threads,
 * Redis cross-server synchronization, and Multi-Currency support.
 */
@SuppressWarnings("deprecation")
public class OptimizedEconomy implements MultiCurrencyEconomy, VaultAsyncEconomy, VaultLeaderboardAPI, VaultBatchTransactionAPI, VaultFormatAPI, VaultMailboxAPI, VaultBoosterAPI, VaultLockAPI, VaultSubscriptionAPI, VaultAnalyticsAPI, VaultCurrencyRegistry, VaultAuditAPI, VaultCheckAPI, VaultLoanAPI, VaultInflationAPI, VaultMilestoneAPI, VaultCryptoAPI, VaultAuctionAPI, VaultStakingAPI, VaultTaxAPI, VaultCreditAPI {

    private final Economy delegate;
    private final boolean useCache;
    private final boolean debugTransactions;
    private final org.bukkit.plugin.Plugin plugin;
    private final boolean rateLimiterEnabled;
    private final int maxTps;
    private final int cooldownSeconds;
    private final boolean nativeBanks;
    private final boolean autoConvert;
    private final org.bukkit.configuration.ConfigurationSection exchangeRates;
    private final long onlineCacheTtlMs;
    private final java.util.concurrent.ExecutorService asyncExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    // Cache entry record for player balances (Java 21 zero-boilerplate)
    private record CacheEntry(double balance, long timestamp) {}

    // Thread-safe cache for high-throughput operations. Key format: Map<UUID, Map<currency, CacheEntry>>
    private final Map<UUID, Map<String, CacheEntry>> balanceCache = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicLong cacheHits = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong cacheMisses = new java.util.concurrent.atomic.AtomicLong(0);

    public long getCacheHits() {
        return cacheHits.get();
    }

    public long getCacheMisses() {
        return cacheMisses.get();
    }
    
    private final Map<UUID, Map<String, CacheEntry>> offlineBalanceCache = new ConcurrentHashMap<>();
    private static final long OFFLINE_CACHE_TTL_MS = 10000L; // 10 seconds TTL
    
    private final Map<UUID, Long> negativeAccountCache = new ConcurrentHashMap<>();
    private static final long NEGATIVE_CACHE_TTL_MS = 30000L; // 30 seconds TTL
    
    // Rate limiter state
    private final Map<UUID, Long> rateLimitWindow = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> rateLimitCount = new ConcurrentHashMap<>();
    private final Map<UUID, Long> rateLimitBlock = new ConcurrentHashMap<>();
    
    // Bank cache
    private final Map<String, Double> bankBalances = new ConcurrentHashMap<>();

    // Booster cache
    private final Map<String, Double> globalBoosters = new ConcurrentHashMap<>();
    private final Map<String, Long> globalBoosterExpirations = new ConcurrentHashMap<>();

    // Lock, Subscription and Registry cache
    private final StripedLock stripedLock = new StripedLock();
    private final Map<String, SubscriptionDetails> activeSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, CustomCurrencyProvider> customProviders = new ConcurrentHashMap<>();
 
    private final org.bukkit.scheduler.BukkitTask cleanupTask;

    public OptimizedEconomy(org.bukkit.plugin.Plugin plugin, Economy delegate, boolean useCache, boolean debugTransactions, boolean rateLimiterEnabled, int maxTps, int cooldownSeconds, boolean nativeBanks) {
        this.plugin = plugin;
        this.delegate = delegate;
        this.useCache = useCache;
        this.debugTransactions = debugTransactions;
        this.rateLimiterEnabled = rateLimiterEnabled;
        this.maxTps = maxTps;
        this.cooldownSeconds = cooldownSeconds;
        this.nativeBanks = nativeBanks;
        this.autoConvert = plugin.getConfig().getBoolean("currency-exchange.auto-convert", false);
        this.exchangeRates = plugin.getConfig().getConfigurationSection("currency-exchange.rates");
        this.onlineCacheTtlMs = plugin.getConfig().getLong("economy.cache-ttl-ms", 1000L);
        
        // Clean up rate limiting state and expired cache entries every 30 seconds
        this.cleanupTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, () -> {
            long now = System.currentTimeMillis();
            
            // Prune online and offline balance cache entries
            if (this.useCache) {
                balanceCache.entrySet().removeIf(entry -> {
                    UUID uuid = entry.getKey();
                    if (Bukkit.getPlayer(uuid) == null) {
                        return true;
                    }
                    Map<String, CacheEntry> inner = entry.getValue();
                    if (inner != null) {
                        inner.entrySet().removeIf(e -> (now - e.getValue().timestamp) > (onlineCacheTtlMs * 10));
                        return inner.isEmpty();
                    }
                    return true;
                });

                offlineBalanceCache.entrySet().removeIf(entry -> {
                    Map<String, CacheEntry> inner = entry.getValue();
                    if (inner != null) {
                        inner.entrySet().removeIf(e -> (now - e.getValue().timestamp) > OFFLINE_CACHE_TTL_MS);
                        return inner.isEmpty();
                    }
                    return true;
                });
            }
            
            negativeAccountCache.values().removeIf(expiry -> now > expiry);
            
            // Prune expired rate limit entries (leak prevention for offline players)
            rateLimitWindow.entrySet().removeIf(e -> (now - e.getValue()) > 1000L);
            rateLimitCount.entrySet().removeIf(e -> !rateLimitWindow.containsKey(e.getKey()));
            rateLimitBlock.entrySet().removeIf(e -> now > e.getValue());
        }, 300L, 300L);
        preloadBanks();
    }

    private boolean isRateLimited(OfflinePlayer player) {
        if (!rateLimiterEnabled || player == null) return false;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (rateLimitBlock.containsKey(uuid)) {
            if (now < rateLimitBlock.get(uuid)) return true;
            rateLimitBlock.remove(uuid);
        }

        long windowStart = rateLimitWindow.getOrDefault(uuid, 0L);
        if (now - windowStart > 1000) {
            rateLimitWindow.put(uuid, now);
            rateLimitCount.put(uuid, 1);
        } else {
            int count = rateLimitCount.getOrDefault(uuid, 0) + 1;
            rateLimitCount.put(uuid, count);
            if (count > maxTps) {
                rateLimitBlock.put(uuid, now + (cooldownSeconds * 1000L));
                if (player.isOnline() && player.getPlayer() != null) {
                    plugin.getLogger().warning("[Vault Security] Player " + player.getName() + " exceeded transaction rate limit! Blocked for " + cooldownSeconds + "s.");
                }
                if (net.milkbowl.vault.Vault.getFirewall() != null) {
                    net.milkbowl.vault.Vault.getFirewall().notifyRateLimit(player, count, maxTps, cooldownSeconds);
                }
                return true;
            }
        }
        return false;
    }

    public void updateBankCacheFromRedis(String bankName, double balance) {
        bankBalances.put(bankName.toLowerCase(), balance);
    }

    public Economy getDelegate() {
        return delegate;
    }

    private double getNativeDefaultBalance(OfflinePlayer player) {
        if (player == null) return 0.0;
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null) {
            return redis.getCustomCurrencyBalance(player.getUniqueId(), "default");
        }
        return net.milkbowl.vault.Vault.getFailoverManager().getCustomCurrencyBalance(player.getUniqueId(), "default");
    }

    private double getNativeDefaultBalance(String playerName) {
        if (playerName == null) return 0.0;
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op == null) return 0.0;
        return getNativeDefaultBalance(op);
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
        return bankBalances;
    }

    private OfflinePlayer resolvePlayerFast(String playerName) {
        return net.milkbowl.vault.util.UUIDCache.getOfflinePlayerFast(playerName);
    }

    public void preloadBanks() {
        if (!nativeBanks) return;
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            try {
                // 1. Load from local database (SQLite/MySQL)
                Map<String, Double> localBanks = net.milkbowl.vault.Vault.getFailoverManager().loadAllBanks();
                if (localBanks != null) {
                    for (Map.Entry<String, Double> entry : localBanks.entrySet()) {
                        bankBalances.put(entry.getKey().toLowerCase(), entry.getValue());
                    }
                }
                
                // 2. If Redis is enabled and online, load from Redis to overwrite/update with latest values
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

    /**
     * Invalidate and clear cache for a player when they disconnect or manually.
     */
    public void invalidateCache(OfflinePlayer player) {
        if (player != null) {
            UUID uuid = player.getUniqueId();
            rateLimitWindow.remove(uuid);
            rateLimitCount.remove(uuid);
            rateLimitBlock.remove(uuid);
            negativeAccountCache.remove(uuid);
            cryptoWallets.remove(uuid);
            if (useCache) {
                balanceCache.remove(uuid);
                offlineBalanceCache.remove(uuid);
            }
        }
    }

    public void shutdown() {
        if (cleanupTask != null) {
            try {
                cleanupTask.cancel();
            } catch (Exception ignored) {}
        }
        try {
            asyncExecutor.shutdownNow();
            asyncExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        balanceCache.clear();
        offlineBalanceCache.clear();
        negativeAccountCache.clear();
        rateLimitWindow.clear();
        rateLimitCount.clear();
        rateLimitBlock.clear();
        bankBalances.clear();
        globalBoosters.clear();
        globalBoosterExpirations.clear();
        activeSubscriptions.clear();
        customProviders.clear();
        cryptoWallets.clear();
        registeredMilestones.clear();
        inflationRates.clear();
        taxRates.clear();
    }

    public void onPlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();
        Map<String, CacheEntry> offlineBals = offlineBalanceCache.remove(uuid);
        if (offlineBals != null) {
            balanceCache.put(uuid, new ConcurrentHashMap<>(offlineBals));
        } else {
            // Fallback: warm cache asynchronously if missed pre-login
            net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> warmCache(uuid));
        }
    }

    public double getNativeDefaultBalance(UUID uuid) {
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null) {
            return redis.getCustomCurrencyBalance(uuid, "default");
        }
        return net.milkbowl.vault.Vault.getFailoverManager().getCustomCurrencyBalance(uuid, "default");
    }

    public void warmCache(UUID uuid) {
        if (!useCache) return;
        long now = System.currentTimeMillis();
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        
        // 1. Get default balance
        double defaultBal = (delegate != null) ? delegate.getBalance(op) : getNativeDefaultBalance(uuid);
        balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put("default", new CacheEntry(defaultBal, now));
        offlineBalanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put("default", new CacheEntry(defaultBal, now));
        
        // 2. Multi-currency balances
        for (String currency : getSupportedCurrencies()) {
            if (!currency.equalsIgnoreCase("default")) {
                double bal;
                if (delegate instanceof MultiCurrencyEconomy) {
                    bal = ((MultiCurrencyEconomy) delegate).getCurrencyBalance(op, currency);
                } else {
                    VaultRedisManager redis = VaultRedisManager.getInstance();
                    if (redis != null) {
                        bal = redis.getCustomCurrencyBalance(uuid, currency);
                    } else {
                        bal = net.milkbowl.vault.Vault.getFailoverManager().getCustomCurrencyBalance(uuid, currency);
                    }
                }
                balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency.toLowerCase(), new CacheEntry(bal, now));
                offlineBalanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency.toLowerCase(), new CacheEntry(bal, now));
            }
        }
    }

    private void updateCache(OfflinePlayer player, String currency, double newBalance) {
        if (player == null) return;
        if (useCache) {
            String curr = currency == null ? "default" : currency.toLowerCase();
            long now = System.currentTimeMillis();
            if (player.isOnline()) {
                balanceCache.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>()).put(curr, new CacheEntry(newBalance, now));
            } else {
                offlineBalanceCache.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>()).put(curr, new CacheEntry(newBalance, now));
            }
        }
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null) {
            redis.publishBalanceUpdate(player.getUniqueId(), currency == null ? "default" : currency, newBalance);
        }
    }

    public void updateCacheFromRedis(UUID uuid, String currency, double newBalance) {
        if (useCache) {
            String curr = currency == null ? "default" : currency.toLowerCase();
            long now = System.currentTimeMillis();
            Player onlinePlayer = Bukkit.getPlayer(uuid);
            if (onlinePlayer != null) {
                balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(curr, new CacheEntry(newBalance, now));
            } else {
                offlineBalanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(curr, new CacheEntry(newBalance, now));
            }
        }
    }

    private void saveCustomCurrencyBalance(OfflinePlayer player, String currency, double balance) {
        String curr = currency == null ? "default" : currency.toLowerCase();
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null) {
            redis.setCustomCurrencyBalance(player.getUniqueId(), curr, balance);
        } else {
            final UUID uuid = player.getUniqueId();
            final String normalizedCurr = curr;
            final double newBal = balance;
            net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                net.milkbowl.vault.Vault.getFailoverManager().saveCustomCurrencyBalance(uuid, normalizedCurr, newBal);
            });
        }
    }

    public void purgePlayerCache(UUID uuid) {
        if (uuid == null) return;
        balanceCache.remove(uuid);
        offlineBalanceCache.remove(uuid);
        negativeAccountCache.remove(uuid);
        rateLimitWindow.remove(uuid);
        rateLimitCount.remove(uuid);
        rateLimitBlock.remove(uuid);
    }

    private void triggerEventAsync(OfflinePlayer player, double amount, String currency, TransactionType type) {
        triggerEventAsync(player, null, amount, currency, type, null, -1.0);
    }

    private void triggerEventAsync(OfflinePlayer player, OfflinePlayer target, double amount, String currency, TransactionType type, String reason, double newBalance) {
        boolean hasListeners = VaultTransactionEvent.getHandlerList().getRegisteredListeners().length > 0;
        boolean hasFailover = net.milkbowl.vault.Vault.getFailoverManager() != null && player != null;

        if (!hasListeners && !hasFailover && !debugTransactions) {
            return; // Short-circuit: Zero allocations when no listeners or failover active
        }

        if (debugTransactions) {
            Bukkit.getLogger().info("[Vault Debug] Transaction: " + type + " " + amount + " (" + (currency == null ? "default" : currency) + ") for player " + (player != null ? player.getName() : "Unknown"));
        }
        String caller = findCallerPlugin();
        String curr = currency == null ? "default" : currency;
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            if (hasListeners) {
                VaultTransactionEvent event = new VaultTransactionEvent(player, target, amount, curr, type, caller, reason, newBalance);
                Bukkit.getPluginManager().callEvent(event);
            }
            if (hasFailover) {
                net.milkbowl.vault.Vault.getFailoverManager().savePlayerTransaction(
                        player.getUniqueId(),
                        type.name(),
                        curr,
                        amount,
                        caller
                );
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
                    int idx = className.indexOf('.', className.indexOf('.') + 1); // Get root namespace e.g. fr.skynex
                    if (idx > 0) return className.substring(0, idx);
                    return className;
                }
            }
        } catch (Exception e) {}
        return "Unknown";
    }

    /* --- INTERCEPTED PERFORMANCE CACHING METHODS --- */

    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null) return 0.0;
        if (useCache) {
            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            if (player.isOnline()) {
                Map<String, CacheEntry> playerBals = balanceCache.get(uuid);
                if (playerBals != null) {
                    CacheEntry cached = playerBals.get("default");
                    if (cached != null && (now - cached.timestamp) < getEffectiveTtl()) {
                        cacheHits.incrementAndGet();
                        return cached.balance;
                    }
                }
                cacheMisses.incrementAndGet();
                double bal = (delegate != null) ? delegate.getBalance(player) : getNativeDefaultBalance(player);
                balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put("default", new CacheEntry(bal, now));
                return bal;
            } else {
                if (!player.hasPlayedBefore()) {
                    Long blockedUntil = negativeAccountCache.get(uuid);
                    if (blockedUntil != null && now < blockedUntil) {
                        cacheHits.incrementAndGet();
                        return 0.0;
                    }
                }
                Map<String, CacheEntry> playerBals = offlineBalanceCache.get(uuid);
                if (playerBals != null) {
                    CacheEntry entry = playerBals.get("default");
                    if (entry != null && (now - entry.timestamp) < OFFLINE_CACHE_TTL_MS) {
                        cacheHits.incrementAndGet();
                        return entry.balance;
                    }
                }
                cacheMisses.incrementAndGet();
                double bal = (delegate != null) ? delegate.getBalance(player) : getNativeDefaultBalance(player);
                if (bal == 0.0 && !player.hasPlayedBefore()) {
                    negativeAccountCache.put(uuid, now + NEGATIVE_CACHE_TTL_MS);
                }
                offlineBalanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put("default", new CacheEntry(bal, now));
                return bal;
            }
        }
        return (delegate != null) ? delegate.getBalance(player) : getNativeDefaultBalance(player);
    }

    @Override
    public double getBalance(String playerName) {
        if (playerName == null)
            return 0;
        Player online = Bukkit.getPlayerExact(playerName);
        long now = System.currentTimeMillis();
        if (useCache && online != null) {
            UUID uuid = online.getUniqueId();
            Map<String, CacheEntry> playerBals = balanceCache.get(uuid);
            if (playerBals != null) {
                CacheEntry cached = playerBals.get("default");
                if (cached != null && (now - cached.timestamp) < getEffectiveTtl()) {
                    cacheHits.incrementAndGet();
                    return cached.balance;
                }
            }
            cacheMisses.incrementAndGet();
            double bal = (delegate != null) ? delegate.getBalance(playerName) : getNativeDefaultBalance(playerName);
            balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put("default", new CacheEntry(bal, now));
            return bal;
        }
        if (useCache) {
            OfflinePlayer op = resolvePlayerFast(playerName);
            if (op != null) {
                UUID uuid = op.getUniqueId();
                Map<String, CacheEntry> playerBals = offlineBalanceCache.get(uuid);
                if (playerBals != null) {
                    CacheEntry entry = playerBals.get("default");
                    if (entry != null && (now - entry.timestamp) < OFFLINE_CACHE_TTL_MS) {
                        cacheHits.incrementAndGet();
                        return entry.balance;
                    }
                }
                cacheMisses.incrementAndGet();
                double bal = (delegate != null) ? delegate.getBalance(playerName) : getNativeDefaultBalance(playerName);
                offlineBalanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put("default", new CacheEntry(bal, now));
                return bal;
            }
        }
        return (delegate != null) ? delegate.getBalance(playerName) : getNativeDefaultBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        // Context-dependent balances usually bypass primary caches or share same logic
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

    private EconomyResponse executeTransaction(OfflinePlayer player, double amount, String currency, String type, TransactionType eventType, EconomyTransaction transaction) {
        if (isRateLimited(player)) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Rate limit exceeded");
        if (player == null) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player cannot be null");
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid transaction amount");
        }

        double currentBalance = (currency == null) ? getBalance(player) : getCurrencyBalance(player, currency);
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
                updateCache(player, currency, response.balance);
                triggerEventAsync(player, amount, currency, eventType);
                if (net.milkbowl.vault.Vault.getFirewall() != null && eventType == TransactionType.DEPOSIT) {
                    net.milkbowl.vault.Vault.getFirewall().recordTransaction(player, amount);
                }
                if (player.isOnline() && player.getPlayer() != null) {
                    Player onlinePlayer = player.getPlayer();
                    net.milkbowl.vault.util.FoliaScheduler.runEntitySync(plugin, onlinePlayer, () -> 
                        net.milkbowl.vault.util.VaultXVisuals.sendTransactionNotification(
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

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return executeTransaction(player, amount, null, "WITHDRAW", TransactionType.WITHDRAW,
                () -> (delegate != null) ? delegate.withdrawPlayer(player, amount) : withdrawNativeDefault(player, amount));
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op != null) return withdrawPlayer(op, amount);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return executeTransaction(player, amount, null, "DEPOSIT", TransactionType.DEPOSIT,
                () -> (delegate != null) ? delegate.depositPlayer(player, amount) : depositNativeDefault(player, amount));
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op != null) return depositPlayer(op, amount);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return executeTransaction(player, amount, null, "WITHDRAW", TransactionType.WITHDRAW,
                () -> (delegate != null) ? delegate.withdrawPlayer(player, worldName, amount) : withdrawNativeDefault(player, amount));
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op != null) return withdrawPlayer(op, worldName, amount);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return executeTransaction(player, amount, null, "DEPOSIT", TransactionType.DEPOSIT,
                () -> (delegate != null) ? delegate.depositPlayer(player, worldName, amount) : depositNativeDefault(player, amount));
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op != null) return depositPlayer(op, worldName, amount);
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
        if (nativeBanks) return true;
        return delegate != null ? delegate.hasBankSupport() : false;
    }

    private double getBankBalanceNative(String name) {
        String key = name.toLowerCase();
        if (bankBalances.containsKey(key)) return bankBalances.get(key);
        double bal = 0.0;
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            bal = redis.getBankBalance(name);
        } else {
            bal = net.milkbowl.vault.Vault.getFailoverManager().getBankBalance(name);
        }
        bankBalances.put(key, bal);
        return bal;
    }

    @Override
    public int fractionalDigits() {
        return delegate != null ? delegate.fractionalDigits() : 2;
    }

    @Override
    public String format(double amount) {
        if (delegate != null) return delegate.format(amount);
        String symbol = plugin.getConfig().getString("formatting.symbol", "$");
        String position = plugin.getConfig().getString("formatting.symbol-position", "AFTER");
        int decimals = plugin.getConfig().getInt("formatting.decimal-places", 2);
        boolean shortFormat = plugin.getConfig().getBoolean("formatting.use-short-format", false);

        if (shortFormat) {
            String formatted;
            if (amount >= 1_000_000_000) {
                formatted = String.format(java.util.Locale.US, "%.2fB", amount / 1_000_000_000.0);
            } else if (amount >= 1_000_000) {
                formatted = String.format(java.util.Locale.US, "%.2fM", amount / 1_000_000.0);
            } else if (amount >= 1_000) {
                formatted = String.format(java.util.Locale.US, "%.2fk", amount / 1_000.0);
            } else {
                formatted = String.format(java.util.Locale.US, "%." + decimals + "f", amount);
            }
            return "BEFORE".equalsIgnoreCase(position) ? symbol + formatted : formatted + symbol;
        }

        String fmt = "%." + decimals + "f";
        String val = String.format(java.util.Locale.US, fmt, amount);
        return "BEFORE".equalsIgnoreCase(position) ? symbol + val : val + symbol;
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
        if (!nativeBanks && delegate != null) return delegate.createBank(name, player);
        if (bankBalances.containsKey(name.toLowerCase())) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bank exists");
        bankBalances.put(name.toLowerCase(), 0.0);
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            redis.setBankBalance(name, 0.0);
        } else {
            net.milkbowl.vault.Vault.getFailoverManager().saveBankBalance(name, 0.0);
        }
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.SUCCESS, "");
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return createBank(name, player != null ? player.getName() : "");
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        if (!nativeBanks && delegate != null) return delegate.deleteBank(name);
        bankBalances.remove(name.toLowerCase());
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            redis.setBankBalance(name, 0.0);
        } else {
            net.milkbowl.vault.Vault.getFailoverManager().deleteBankAccount(name);
        }
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.SUCCESS, "");
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        if (!nativeBanks && delegate != null) return delegate.bankBalance(name);
        return new EconomyResponse(0, getBankBalanceNative(name), EconomyResponse.ResponseType.SUCCESS, "");
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        if (!nativeBanks && delegate != null) return delegate.bankHas(name, amount);
        double bal = getBankBalanceNative(name);
        if (bal >= amount) return new EconomyResponse(0, bal, EconomyResponse.ResponseType.SUCCESS, "");
        return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Not enough funds");
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        if (!nativeBanks && delegate != null) return delegate.bankWithdraw(name, amount);
        double bal = getBankBalanceNative(name);
        if (bal >= amount) {
            bal -= amount;
            bankBalances.put(name.toLowerCase(), bal);
            VaultRedisManager redis = VaultRedisManager.getInstance();
            if (redis != null && redis.isOnline()) {
                redis.setBankBalance(name, bal);
            } else {
                net.milkbowl.vault.Vault.getFailoverManager().saveBankBalance(name, bal);
            }
            return new EconomyResponse(amount, bal, EconomyResponse.ResponseType.SUCCESS, "");
        }
        return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Not enough funds");
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        if (!nativeBanks && delegate != null) return delegate.bankDeposit(name, amount);
        double bal = getBankBalanceNative(name) + amount;
        bankBalances.put(name.toLowerCase(), bal);
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            redis.setBankBalance(name, bal);
        } else {
            net.milkbowl.vault.Vault.getFailoverManager().saveBankBalance(name, bal);
        }
        return new EconomyResponse(amount, bal, EconomyResponse.ResponseType.SUCCESS, "");
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        if (!nativeBanks && delegate != null) return delegate.isBankOwner(name, playerName);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "VaultX Banks are shared");
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return isBankOwner(name, "");
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        if (!nativeBanks && delegate != null) return delegate.isBankMember(name, playerName);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "VaultX Banks are shared");
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return isBankMember(name, "");
    }

    @Override
    public java.util.List<String> getBanks() {
        if (!nativeBanks && delegate != null) return delegate.getBanks();
        return new java.util.ArrayList<>(bankBalances.keySet());
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

    private Double invokeDelegateGetCurrencyBalance(OfflinePlayer player, String currency) {
        if (delegate == null) return null;
        if (delegate instanceof MultiCurrencyEconomy) {
            return ((MultiCurrencyEconomy) delegate).getCurrencyBalance(player, currency);
        }
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("getCurrencyBalance", OfflinePlayer.class, String.class);
            return (Double) m.invoke(delegate, player, currency);
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("getBalance", OfflinePlayer.class, String.class);
            return (Double) m.invoke(delegate, player, currency);
        } catch (Throwable ignored) {}
        return null;
    }

    private Double invokeDelegateGetCurrencyBalance(String playerName, String currency) {
        if (delegate == null) return null;
        if (delegate instanceof MultiCurrencyEconomy) {
            return ((MultiCurrencyEconomy) delegate).getCurrencyBalance(playerName, currency);
        }
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("getCurrencyBalance", String.class, String.class);
            return (Double) m.invoke(delegate, playerName, currency);
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("getBalance", String.class, String.class);
            return (Double) m.invoke(delegate, playerName, currency);
        } catch (Throwable ignored) {}
        return null;
    }

    private EconomyResponse invokeDelegateWithdrawCurrency(OfflinePlayer player, String currency, double amount) {
        if (delegate == null) return null;
        if (delegate instanceof MultiCurrencyEconomy) {
            return ((MultiCurrencyEconomy) delegate).withdrawCurrencyPlayer(player, currency, amount);
        }
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("withdrawCurrencyPlayer", OfflinePlayer.class, String.class, double.class);
            return (EconomyResponse) m.invoke(delegate, player, currency, amount);
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("withdrawPlayer", OfflinePlayer.class, String.class, double.class);
            return (EconomyResponse) m.invoke(delegate, player, currency, amount);
        } catch (Throwable ignored) {}
        return null;
    }

    private EconomyResponse invokeDelegateDepositCurrency(OfflinePlayer player, String currency, double amount) {
        if (delegate == null) return null;
        if (delegate instanceof MultiCurrencyEconomy) {
            return ((MultiCurrencyEconomy) delegate).depositCurrencyPlayer(player, currency, amount);
        }
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("depositCurrencyPlayer", OfflinePlayer.class, String.class, double.class);
            return (EconomyResponse) m.invoke(delegate, player, currency, amount);
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = delegate.getClass().getMethod("depositPlayer", OfflinePlayer.class, String.class, double.class);
            return (EconomyResponse) m.invoke(delegate, player, currency, amount);
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public java.util.List<String> getSupportedCurrencies() {
        if (delegate instanceof MultiCurrencyEconomy) {
            return ((MultiCurrencyEconomy) delegate).getSupportedCurrencies();
        }
        if (delegate != null) {
            try {
                java.lang.reflect.Method m = delegate.getClass().getMethod("getSupportedCurrencies");
                Object res = m.invoke(delegate);
                if (res instanceof java.util.List) return (java.util.List<String>) res;
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Method m = delegate.getClass().getMethod("getCurrencies");
                Object res = m.invoke(delegate);
                if (res instanceof java.util.List) return (java.util.List<String>) res;
            } catch (Throwable ignored) {}
        }
        java.util.List<String> currencies = new java.util.ArrayList<>();
        currencies.add("default");
        if (exchangeRates != null) {
            for (String key : exchangeRates.getKeys(false)) {
                String normalized = key.toLowerCase();
                if (!normalized.equals("default") && !currencies.contains(normalized)) {
                    currencies.add(normalized);
                }
            }
        }
        return currencies;
    }

    @Override
    public double getCurrencyBalance(OfflinePlayer player, String currency) {
        if (player == null || currency == null) return 0.0;
        if (currency.equalsIgnoreCase("default")) {
            return getBalance(player);
        }
        String curr = currency.toLowerCase();
        if (useCache) {
            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            if (player.isOnline()) {
                Map<String, CacheEntry> playerBals = balanceCache.get(uuid);
                if (playerBals != null) {
                    CacheEntry cached = playerBals.get(curr);
                    if (cached != null && (now - cached.timestamp) < getEffectiveTtl()) {
                        cacheHits.incrementAndGet();
                        return cached.balance;
                    }
                }
                cacheMisses.incrementAndGet();
                Double delegateBal = invokeDelegateGetCurrencyBalance(player, currency);
                double bal;
                if (delegateBal != null) {
                    bal = delegateBal;
                } else {
                    VaultRedisManager redis = VaultRedisManager.getInstance();
                    if (redis != null) {
                        bal = redis.getCustomCurrencyBalance(player.getUniqueId(), currency);
                    } else {
                        bal = net.milkbowl.vault.Vault.getFailoverManager().getCustomCurrencyBalance(player.getUniqueId(), currency);
                    }
                }
                balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(curr, new CacheEntry(bal, now));
                return bal;
            } else {
                if (!player.hasPlayedBefore()) {
                    Long blockedUntil = negativeAccountCache.get(uuid);
                    if (blockedUntil != null && now < blockedUntil) {
                        cacheHits.incrementAndGet();
                        return 0.0;
                    }
                }
                Map<String, CacheEntry> playerBals = offlineBalanceCache.get(uuid);
                if (playerBals != null) {
                    CacheEntry entry = playerBals.get(curr);
                    if (entry != null && (now - entry.timestamp) < OFFLINE_CACHE_TTL_MS) {
                        cacheHits.incrementAndGet();
                        return entry.balance;
                    }
                }
                cacheMisses.incrementAndGet();
                Double delegateBal = invokeDelegateGetCurrencyBalance(player, currency);
                double bal;
                if (delegateBal != null) {
                    bal = delegateBal;
                } else {
                    VaultRedisManager redis = VaultRedisManager.getInstance();
                    if (redis != null) {
                        bal = redis.getCustomCurrencyBalance(player.getUniqueId(), currency);
                    } else {
                        bal = net.milkbowl.vault.Vault.getFailoverManager().getCustomCurrencyBalance(player.getUniqueId(), currency);
                    }
                }
                if (bal == 0.0 && !player.hasPlayedBefore()) {
                    negativeAccountCache.put(uuid, now + NEGATIVE_CACHE_TTL_MS);
                }
                offlineBalanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(curr, new CacheEntry(bal, now));
                return bal;
            }
        }
        Double delegateBal = invokeDelegateGetCurrencyBalance(player, currency);
        if (delegateBal != null) {
            return delegateBal;
        }
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null) {
            return redis.getCustomCurrencyBalance(player.getUniqueId(), currency);
        }
        return net.milkbowl.vault.Vault.getFailoverManager().getCustomCurrencyBalance(player.getUniqueId(), currency);
    }

    @Override
    public double getCurrencyBalance(String playerName, String currency) {
        if (playerName == null || currency == null) return 0.0;
        if (currency.equalsIgnoreCase("default")) {
            return getBalance(playerName);
        }
        String curr = currency.toLowerCase();
        Player online = Bukkit.getPlayerExact(playerName);
        long now = System.currentTimeMillis();
        if (useCache && online != null) {
            UUID uuid = online.getUniqueId();
            Map<String, CacheEntry> playerBals = balanceCache.get(uuid);
            if (playerBals != null) {
                CacheEntry cached = playerBals.get(curr);
                if (cached != null && (now - cached.timestamp) < getEffectiveTtl()) {
                    cacheHits.incrementAndGet();
                    return cached.balance;
                }
            }
            cacheMisses.incrementAndGet();
            Double delegateBal = invokeDelegateGetCurrencyBalance(playerName, currency);
            double bal;
            if (delegateBal != null) {
                bal = delegateBal;
            } else {
                VaultRedisManager redis = VaultRedisManager.getInstance();
                if (redis != null) {
                    bal = redis.getCustomCurrencyBalance(online.getUniqueId(), currency);
                } else {
                    bal = net.milkbowl.vault.Vault.getFailoverManager().getCustomCurrencyBalance(online.getUniqueId(), currency);
                }
            }
            balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(curr, new CacheEntry(bal, now));
            return bal;
        }
        if (useCache) {
            OfflinePlayer op = resolvePlayerFast(playerName);
            if (op != null) {
                UUID uuid = op.getUniqueId();
                Map<String, CacheEntry> playerBals = offlineBalanceCache.get(uuid);
                if (playerBals != null) {
                    CacheEntry entry = playerBals.get(curr);
                    if (entry != null && (now - entry.timestamp) < OFFLINE_CACHE_TTL_MS) {
                        cacheHits.incrementAndGet();
                        return entry.balance;
                    }
                }
                cacheMisses.incrementAndGet();
                Double delegateBal = invokeDelegateGetCurrencyBalance(playerName, currency);
                double bal;
                if (delegateBal != null) {
                    bal = delegateBal;
                } else {
                    VaultRedisManager redis = VaultRedisManager.getInstance();
                    if (redis != null) {
                        bal = redis.getCustomCurrencyBalance(op.getUniqueId(), currency);
                    } else {
                        bal = net.milkbowl.vault.Vault.getFailoverManager().getCustomCurrencyBalance(op.getUniqueId(), currency);
                    }
                }
                offlineBalanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(curr, new CacheEntry(bal, now));
                return bal;
            }
        }
        Double delegateBal = invokeDelegateGetCurrencyBalance(playerName, currency);
        if (delegateBal != null) {
            return delegateBal;
        }
        OfflinePlayer op = resolvePlayerFast(playerName);
        if (op != null) {
            VaultRedisManager redis = VaultRedisManager.getInstance();
            if (redis != null) {
                return redis.getCustomCurrencyBalance(op.getUniqueId(), currency);
            }
            return net.milkbowl.vault.Vault.getFailoverManager().getCustomCurrencyBalance(op.getUniqueId(), currency);
        }
        return 0.0;
    }

    @Override
    public EconomyResponse withdrawCurrencyPlayer(OfflinePlayer player, String currency, double amount) {
        return executeTransaction(player, amount, currency, "WITHDRAW_" + currency, TransactionType.WITHDRAW, () -> {
            EconomyResponse delegateRes = invokeDelegateWithdrawCurrency(player, currency, amount);
            if (delegateRes != null) {
                if (!delegateRes.transactionSuccess() && autoConvert && exchangeRates != null) {
                    double rate = exchangeRates.getDouble(currency, 0.0);
                    if (rate > 0) {
                        double defaultNeeded = amount * rate;
                        if (getBalance(player) >= defaultNeeded) {
                            EconomyResponse wRes = withdrawPlayer(player, defaultNeeded);
                            if (wRes.transactionSuccess()) {
                                delegateRes = new EconomyResponse(amount, getCurrencyBalance(player, currency), EconomyResponse.ResponseType.SUCCESS, "Auto-converted from default currency");
                            }
                        }
                    }
                }
                return delegateRes;
            } else {
                if (currency.equalsIgnoreCase("default")) {
                    return (delegate != null) ? delegate.withdrawPlayer(player, amount) : withdrawNativeDefault(player, amount);
                } else {
                    double bal = getCurrencyBalance(player, currency);
                    if (bal >= amount) {
                        bal -= amount;
                        saveCustomCurrencyBalance(player, currency, bal);
                        return new EconomyResponse(amount, bal, EconomyResponse.ResponseType.SUCCESS, "");
                    } else if (autoConvert && exchangeRates != null) {
                        double rate = exchangeRates.getDouble(currency, 0.0);
                        if (rate > 0) {
                            double defaultNeeded = amount * rate;
                            if (getBalance(player) >= defaultNeeded) {
                                EconomyResponse wRes = withdrawPlayer(player, defaultNeeded);
                                if (wRes.transactionSuccess()) {
                                    return new EconomyResponse(amount, bal, EconomyResponse.ResponseType.SUCCESS, "Auto-converted from default currency");
                                } else {
                                    return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Failed to auto-convert from default currency");
                                }
                            } else {
                                return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Not enough funds (including exchange auto-convert)");
                            }
                        } else {
                            return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Not enough funds");
                        }
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
        if (op != null) return withdrawCurrencyPlayer(op, currency, amount);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
    }

    @Override
    public EconomyResponse depositCurrencyPlayer(OfflinePlayer player, String currency, double amount) {
        return executeTransaction(player, amount, currency, "DEPOSIT_" + currency, TransactionType.DEPOSIT, () -> {
            EconomyResponse delegateRes = invokeDelegateDepositCurrency(player, currency, amount);
            if (delegateRes != null) {
                return delegateRes;
            } else {
                if (currency.equalsIgnoreCase("default")) {
                    return (delegate != null) ? delegate.depositPlayer(player, amount) : depositNativeDefault(player, amount);
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
        if (op != null) return depositCurrencyPlayer(op, currency, amount);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
    }

    @Override
    public boolean hasCurrencyAccount(OfflinePlayer player, String currency) {
        if (delegate != null) {
            if (delegate instanceof MultiCurrencyEconomy) {
                return ((MultiCurrencyEconomy) delegate).hasCurrencyAccount(player, currency);
            }
            try {
                java.lang.reflect.Method m = delegate.getClass().getMethod("hasCurrencyAccount", OfflinePlayer.class, String.class);
                return (Boolean) m.invoke(delegate, player, currency);
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Method m = delegate.getClass().getMethod("hasAccount", OfflinePlayer.class, String.class);
                return (Boolean) m.invoke(delegate, player, currency);
            } catch (Throwable ignored) {}
        }
        return delegate != null ? delegate.hasAccount(player) : true;
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
    public java.util.concurrent.CompletableFuture<EconomyResponse> withdrawPlayerAsync(OfflinePlayer player, double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> withdrawPlayer(player, amount), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> withdrawPlayerAsync(String playerName, double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> withdrawPlayer(playerName, amount), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> depositPlayerAsync(OfflinePlayer player, double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> depositPlayer(player, amount), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> depositPlayerAsync(String playerName, double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> depositPlayer(playerName, amount), asyncExecutor);
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
    public java.util.concurrent.CompletableFuture<Double> getCurrencyBalanceAsync(OfflinePlayer player, String currency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> getCurrencyBalance(player, currency), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Double> getCurrencyBalanceAsync(String playerName, String currency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> getCurrencyBalance(playerName, currency), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> withdrawCurrencyPlayerAsync(OfflinePlayer player, String currency, double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> withdrawCurrencyPlayer(player, currency, amount), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> withdrawCurrencyPlayerAsync(String playerName, String currency, double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> withdrawCurrencyPlayer(playerName, currency, amount), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> depositCurrencyPlayerAsync(OfflinePlayer player, String currency, double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> depositCurrencyPlayer(player, currency, amount), asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> depositCurrencyPlayerAsync(String playerName, String currency, double amount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> depositCurrencyPlayer(playerName, currency, amount), asyncExecutor);
    }

    /* --- LEADERBOARD & BATCH TRANSACTION API --- */

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<LeaderboardEntry>> getTopBalancesAsync(String currency, int limit) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            java.util.List<LeaderboardEntry> entries = new java.util.ArrayList<>();
            if (net.milkbowl.vault.Vault.getFailoverManager() != null) {
                Map<UUID, Double> topMap = net.milkbowl.vault.Vault.getFailoverManager().getTopBalances(currency == null ? "default" : currency, limit);
                int rank = 1;
                for (Map.Entry<UUID, Double> entry : topMap.entrySet()) {
                    OfflinePlayer p = Bukkit.getOfflinePlayer(entry.getKey());
                    entries.add(new LeaderboardEntry(entry.getKey(), p != null && p.getName() != null ? p.getName() : "Unknown", entry.getValue(), rank++));
                }
            }
            return entries;
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Integer> getPlayerRankAsync(OfflinePlayer player, String currency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (player == null || net.milkbowl.vault.Vault.getFailoverManager() == null) return -1;
            return net.milkbowl.vault.Vault.getFailoverManager().getPlayerRank(player.getUniqueId(), currency == null ? "default" : currency);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<BatchResult> executeAtomicBatchAsync(java.util.List<BatchOperation> operations) {
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
                    return new BatchResult(false, "Operation failed: " + resp.errorMessage + ". All batch operations rolled back.", responses);
                }
                executed.add(op);
            }
            return new BatchResult(true, null, responses);
        }, asyncExecutor);
    }

    /* --- FORMAT, MAILBOX & BOOSTER API --- */

    @Override
    public String formatCurrency(String currency, double amount) {
        return formatCurrency(currency, amount, java.util.Locale.getDefault());
    }

    @Override
    public String formatCurrency(String currency, double amount, java.util.Locale locale) {
        String sym = getCurrencySymbol(currency);
        java.text.NumberFormat nf = java.text.NumberFormat.getNumberInstance(locale != null ? locale : java.util.Locale.getDefault());
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf.format(amount) + " " + sym;
    }

    @Override
    public String getCurrencySymbol(String currency) {
        if (currency == null || currency.equalsIgnoreCase("default")) return "$";
        if (currency.equalsIgnoreCase("gems")) return "💎";
        if (currency.equalsIgnoreCase("tokens")) return "🪙";
        if (currency.equalsIgnoreCase("coins")) return "🪙";
        return currency.toUpperCase();
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> sendOfflinePaymentAsync(UUID targetUuid, String currency, double amount, String sourceReason) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (targetUuid == null || amount <= 0) return false;
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
            EconomyResponse res = depositCurrencyPlayer(target, currency, amount);
            return res.transactionSuccess();
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> sendOfflinePaymentAsync(OfflinePlayer target, String currency, double amount, String sourceReason) {
        return target != null ? sendOfflinePaymentAsync(target.getUniqueId(), currency, amount, sourceReason) : java.util.concurrent.CompletableFuture.completedFuture(false);
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
        if (player == null || action == null) return;
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
        if (player == null || supplier == null) return null;
        java.util.concurrent.locks.ReentrantLock lock = stripedLock.getLock(player.getUniqueId());
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> registerSubscriptionAsync(OfflinePlayer player, String subscriptionId, String currency, double amount, long intervalMs) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (player == null || subscriptionId == null || amount <= 0 || intervalMs <= 0) return false;
            SubscriptionDetails sub = new SubscriptionDetails(subscriptionId, player.getUniqueId(), currency == null ? "default" : currency, amount, intervalMs, System.currentTimeMillis() + intervalMs);
            activeSubscriptions.put(subscriptionId, sub);
            return true;
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> cancelSubscriptionAsync(String subscriptionId) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (subscriptionId == null) return false;
            return activeSubscriptions.remove(subscriptionId) != null;
        }, asyncExecutor);
    }

    /* --- ANALYTICS, REGISTRY & AUDIT API --- */

    @Override
    public java.util.concurrent.CompletableFuture<Double> getTotalSupplyAsync(String currency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (net.milkbowl.vault.Vault.getFailoverManager() != null) {
                return net.milkbowl.vault.Vault.getFailoverManager().getTotalMoneySupply(currency == null ? "default" : currency);
            }
            return 0.0;
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Double> getAverageBalanceAsync(String currency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (net.milkbowl.vault.Vault.getFailoverManager() != null) {
                return net.milkbowl.vault.Vault.getFailoverManager().getAverageAccountBalance(currency == null ? "default" : currency);
            }
            return 0.0;
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Double> getVolume24hAsync(String currency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (net.milkbowl.vault.Vault.getFailoverManager() != null) {
                return net.milkbowl.vault.Vault.getFailoverManager().getTransactionVolume24h(currency == null ? "default" : currency);
            }
            return 0.0;
        }, asyncExecutor);
    }

    @Override
    public boolean registerCurrency(String currency, CustomCurrencyProvider provider) {
        if (currency == null || provider == null) return false;
        customProviders.put(currency.toLowerCase(), provider);
        return true;
    }

    @Override
    public boolean unregisterCurrency(String currency) {
        if (currency == null) return false;
        return customProviders.remove(currency.toLowerCase()) != null;
    }

    @Override
    public java.util.List<String> getRegisteredCustomCurrencies() {
        return new java.util.ArrayList<>(customProviders.keySet());
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<AuditLogEntry>> getPlayerTransactionHistoryAsync(OfflinePlayer player, int limit) {
        return player != null ? getPlayerTransactionHistoryAsync(player.getUniqueId(), limit) : java.util.concurrent.CompletableFuture.completedFuture(java.util.Collections.emptyList());
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<AuditLogEntry>> getPlayerTransactionHistoryAsync(UUID playerUuid, int limit) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            java.util.List<AuditLogEntry> logs = new java.util.ArrayList<>();
            if (playerUuid != null && net.milkbowl.vault.Vault.getFailoverManager() != null) {
                var records = net.milkbowl.vault.Vault.getFailoverManager().getPlayerTransactions(playerUuid, 1, limit);
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

    private long getEffectiveTtl() {
        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            return Math.max(onlineCacheTtlMs, 30000L);
        }
        return onlineCacheTtlMs;
    }

    // ==========================================
    //            VaultCheckAPI Implementation
    // ==========================================
    @Override
    public java.util.concurrent.CompletableFuture<org.bukkit.inventory.ItemStack> createCheckAsync(OfflinePlayer issuer, String currency, double amount) {
        if (!plugin.getConfig().getBoolean("checks.enabled", true)) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            org.bukkit.inventory.ItemStack check = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
            org.bukkit.inventory.meta.ItemMeta meta = check.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§6§lBank Check §7(§e" + amount + " " + currency + "§7)");
                meta.setLore(java.util.List.of("§7Issued by: §f" + (issuer != null ? issuer.getName() : "Bank"), "§7Amount: §a" + amount, "§7Currency: §e" + currency, "§8[VaultX Check]"));
                check.setItemMeta(meta);
            }
            return check;
        }, asyncExecutor);
    }

    @Override
    public boolean isCheck(org.bukkit.inventory.ItemStack item) {
        if (!plugin.getConfig().getBoolean("checks.enabled", true)) return false;
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return false;
        var lore = item.getItemMeta().getLore();
        return lore != null && lore.stream().anyMatch(l -> l.contains("[VaultX Check]"));
    }

    @Override
    public CheckDetails getCheckDetails(org.bukkit.inventory.ItemStack item) {
        if (!plugin.getConfig().getBoolean("checks.enabled", true) || !isCheck(item)) return null;
        var lore = item.getItemMeta().getLore();
        double amt = 0;
        String curr = "default";
        if (lore != null) {
            for (String line : lore) {
                if (line.contains("Amount: ")) {
                    try { amt = Double.parseDouble(line.split("Amount: ")[1].replace("§a", "").trim()); } catch (Exception ignored) {}
                } else if (line.contains("Currency: ")) {
                    curr = line.split("Currency: ")[1].replace("§e", "").trim();
                }
            }
        }
        return new CheckDetails(UUID.randomUUID().toString(), "Server Bank", curr, amt, System.currentTimeMillis());
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> redeemCheckAsync(OfflinePlayer player, org.bukkit.inventory.ItemStack item) {
        if (!plugin.getConfig().getBoolean("checks.enabled", true)) {
            return java.util.concurrent.CompletableFuture.completedFuture(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Checks feature is disabled in config.yml"));
        }
        CheckDetails details = getCheckDetails(item);
        if (details == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid bank check"));
        }
        return depositPlayerAsync(player, details.amount());
    }

    // ==========================================
    //            VaultLoanAPI Implementation
    // ==========================================
    @Override
    public java.util.concurrent.CompletableFuture<Integer> getCreditScoreAsync(OfflinePlayer player) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            double bal = getBalance(player);
            int score = 650 + (int) Math.min(200, bal / 1000.0);
            return Math.min(850, Math.max(300, score));
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> takeLoanAsync(OfflinePlayer player, String currency, double amount, int durationDays, double interestRate) {
        if (!plugin.getConfig().getBoolean("loans.enabled", true)) {
            return java.util.concurrent.CompletableFuture.completedFuture(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Loans feature is disabled in config.yml"));
        }
        return depositCurrencyPlayerAsync(player, currency, amount);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> repayLoanAsync(OfflinePlayer player, String loanId, double amount) {
        if (!plugin.getConfig().getBoolean("loans.enabled", true)) {
            return java.util.concurrent.CompletableFuture.completedFuture(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Loans feature is disabled in config.yml"));
        }
        return withdrawPlayerAsync(player, amount);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<LoanDetails>> getActiveLoansAsync(OfflinePlayer player) {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of());
    }

    // ==========================================
    //          VaultInflationAPI Implementation
    // ==========================================
    private final Map<String, Double> inflationRates = new ConcurrentHashMap<>();
    private final Map<String, Double> taxRates = new ConcurrentHashMap<>();

    @Override
    public double getInflationRate(String currency) {
        return inflationRates.getOrDefault(currency, 1.0);
    }

    @Override
    public void setInflationRate(String currency, double multiplier) {
        if (currency != null) inflationRates.put(currency, multiplier);
    }

    @Override
    public double getTransactionTaxRate(String currency) {
        return taxRates.getOrDefault(currency, 0.0);
    }

    @Override
    public void setTransactionTaxRate(String currency, double taxPercentage) {
        if (currency != null) taxRates.put(currency, taxPercentage);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Double> applyProgressiveWealthTaxAsync(String currency, double taxPercentage) {
        return java.util.concurrent.CompletableFuture.completedFuture(0.0);
    }

    // ==========================================
    //          VaultMilestoneAPI Implementation
    // ==========================================
    private final Map<String, Milestone> registeredMilestones = new ConcurrentHashMap<>();

    @Override
    public void registerMilestone(Milestone milestone) {
        if (milestone != null && plugin.getConfig().getBoolean("milestones.enabled", true)) {
            registeredMilestones.put(milestone.milestoneId(), milestone);
        }
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<String>> getPlayerMilestonesAsync(OfflinePlayer player) {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of());
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> hasReachedMilestoneAsync(OfflinePlayer player, String milestoneId) {
        if (!plugin.getConfig().getBoolean("milestones.enabled", true)) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        Milestone m = registeredMilestones.get(milestoneId);
        if (m == null) return java.util.concurrent.CompletableFuture.completedFuture(false);
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> getBalance(player) >= m.requiredBalance(), asyncExecutor);
    }

    // ==========================================
    //           VaultCryptoAPI Implementation
    // ==========================================
    private final Map<UUID, Map<String, Double>> cryptoWallets = new ConcurrentHashMap<>();

    @Override
    public java.util.concurrent.CompletableFuture<CryptoWallet> getWalletAsync(OfflinePlayer player, String cryptoName) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            UUID uuid = player != null ? player.getUniqueId() : UUID.randomUUID();
            double bal = cryptoWallets.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).getOrDefault(cryptoName, 0.0);
            return new CryptoWallet("vx_" + uuid.toString().substring(0, 8), cryptoName, bal);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> mineTokensAsync(OfflinePlayer player, String cryptoName, double amount) {
        if (!plugin.getConfig().getBoolean("crypto.enabled", true) || !plugin.getConfig().getBoolean("crypto.mining-enabled", true)) {
            return java.util.concurrent.CompletableFuture.completedFuture(new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Crypto mining is disabled in config.yml"));
        }
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (player != null) {
                cryptoWallets.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>()).merge(cryptoName, amount, Double::sum);
            }
            return new EconomyResponse(amount, amount, EconomyResponse.ResponseType.SUCCESS, "Mined " + amount + " " + cryptoName);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> transferCryptoAsync(String fromAddress, String toAddress, String cryptoName, double amount) {
        if (!plugin.getConfig().getBoolean("crypto.enabled", true)) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        return java.util.concurrent.CompletableFuture.completedFuture(true);
    }

    // ==========================================
    //           VaultAuctionAPI Implementation
    // ==========================================
    private final Map<String, VaultAuctionAPI.AuctionListing> activeAuctions = new ConcurrentHashMap<>();

    @Override
    public java.util.concurrent.CompletableFuture<VaultAuctionAPI.AuctionListing> createAuctionAsync(OfflinePlayer seller, org.bukkit.inventory.ItemStack item, String currency, double startingPrice, long durationMinutes) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (seller == null || item == null || startingPrice <= 0) return null;
            String auctionId = "auc_" + UUID.randomUUID().toString().substring(0, 8);
            long now = System.currentTimeMillis();
            long expiresAt = now + (durationMinutes * 60L * 1000L);
            VaultAuctionAPI.AuctionListing listing = new VaultAuctionAPI.AuctionListing(
                    auctionId, seller.getUniqueId(), item, currency == null ? "default" : currency,
                    startingPrice, startingPrice, null, durationMinutes * 60L * 1000L, expiresAt, false
            );
            activeAuctions.put(auctionId, listing);
            return listing;
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> placeBidAsync(OfflinePlayer bidder, String auctionId, double bidAmount) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (bidder == null || auctionId == null) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid bidder or auctionId");
            }
            VaultAuctionAPI.AuctionListing listing = activeAuctions.get(auctionId);
            if (listing == null || listing.isClosed() || System.currentTimeMillis() > listing.expiresAtMs()) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Auction closed or expired");
            }
            if (bidAmount <= listing.currentBid()) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Bid amount must be higher than current bid");
            }
            var res = withdrawCurrencyPlayer(bidder, listing.currency(), bidAmount);
            if (!res.transactionSuccess()) return res;

            if (listing.highestBidderUuid() != null) {
                depositCurrencyPlayer(org.bukkit.Bukkit.getOfflinePlayer(listing.highestBidderUuid()), listing.currency(), listing.currentBid());
            }

            VaultAuctionAPI.AuctionListing updated = new VaultAuctionAPI.AuctionListing(
                    listing.auctionId(), listing.sellerUuid(), listing.item(), listing.currency(),
                    listing.startingPrice(), bidAmount, bidder.getUniqueId(), listing.durationMs(), listing.expiresAtMs(), false
            );
            activeAuctions.put(auctionId, updated);
            return new EconomyResponse(bidAmount, getCurrencyBalance(bidder, listing.currency()), EconomyResponse.ResponseType.SUCCESS, "Bid placed successfully");
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> cancelAuctionAsync(OfflinePlayer seller, String auctionId) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (seller == null || auctionId == null) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid arguments");
            }
            VaultAuctionAPI.AuctionListing listing = activeAuctions.get(auctionId);
            if (listing == null || !listing.sellerUuid().equals(seller.getUniqueId())) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Auction not found or unauthorized");
            }
            if (listing.highestBidderUuid() != null) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot cancel auction with active bids");
            }
            activeAuctions.remove(auctionId);
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.SUCCESS, "Auction cancelled");
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<VaultAuctionAPI.AuctionListing>> getActiveAuctionsAsync() {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            long now = System.currentTimeMillis();
            return activeAuctions.values().stream()
                    .filter(a -> !a.isClosed() && a.expiresAtMs() > now)
                    .toList();
        }, asyncExecutor);
    }

    // ==========================================
    //           VaultStakingAPI Implementation
    // ==========================================
    private final Map<String, VaultStakingAPI.StakeDeposit> activeStakes = new ConcurrentHashMap<>();

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> createStakeAsync(OfflinePlayer player, String currency, double amount, int durationDays) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (player == null || amount <= 0 || durationDays <= 0) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid staking parameters");
            }
            String curr = currency == null ? "default" : currency;
            var res = withdrawCurrencyPlayer(player, curr, amount);
            if (!res.transactionSuccess()) return res;

            String stakeId = "stake_" + UUID.randomUUID().toString().substring(0, 8);
            long now = System.currentTimeMillis();
            long lockMs = durationDays * 86400000L;
            double rate = 0.05 * (durationDays / 30.0 + 1.0);

            VaultStakingAPI.StakeDeposit deposit = new VaultStakingAPI.StakeDeposit(
                    stakeId, player.getUniqueId(), curr, amount, rate, now, lockMs, false, false
            );
            activeStakes.put(stakeId, deposit);
            return new EconomyResponse(amount, getCurrencyBalance(player, curr), EconomyResponse.ResponseType.SUCCESS, "Staked " + amount + " " + curr);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> claimStakeAsync(OfflinePlayer player, String depositId) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (player == null || depositId == null) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid arguments");
            }
            VaultStakingAPI.StakeDeposit deposit = activeStakes.get(depositId);
            if (deposit == null || !deposit.playerUuid().equals(player.getUniqueId())) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Stake deposit not found");
            }
            if (deposit.isClaimed()) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Stake deposit already claimed");
            }
            long now = System.currentTimeMillis();
            if (now < deposit.stakedAtMs() + deposit.lockPeriodMs()) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Stake deposit is still locked");
            }

            double totalPayout = deposit.principal() * (1.0 + deposit.interestRate());
            var res = depositCurrencyPlayer(player, deposit.currency(), totalPayout);
            if (res.transactionSuccess()) {
                activeStakes.put(depositId, new VaultStakingAPI.StakeDeposit(
                        deposit.depositId(), deposit.playerUuid(), deposit.currency(), deposit.principal(),
                        deposit.interestRate(), deposit.stakedAtMs(), deposit.lockPeriodMs(), true, true
                ));
            }
            return res;
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<VaultStakingAPI.StakeDeposit>> getActiveStakesAsync(OfflinePlayer player) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (player == null) return java.util.Collections.emptyList();
            return activeStakes.values().stream()
                    .filter(s -> s.playerUuid().equals(player.getUniqueId()) && !s.isClaimed())
                    .toList();
        }, asyncExecutor);
    }

    // ==========================================
    //           VaultTaxAPI Implementation
    // ==========================================
    private final Map<String, VaultTaxAPI.TaxRule> taxRules = new ConcurrentHashMap<>();

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> registerTaxRuleAsync(VaultTaxAPI.TaxRule rule) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (rule == null || rule.taxId() == null) return false;
            taxRules.put(rule.taxId(), rule);
            return true;
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> unregisterTaxRuleAsync(String taxId) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (taxId == null) return false;
            return taxRules.remove(taxId) != null;
        }, asyncExecutor);
    }

    @Override
    public double calculateTax(String regionOrWorld, String currency, double amount) {
        if (amount <= 0) return 0.0;
        double totalTax = 0.0;
        for (VaultTaxAPI.TaxRule rule : taxRules.values()) {
            if (rule.regionOrWorld().equalsIgnoreCase(regionOrWorld) && rule.currency().equalsIgnoreCase(currency)) {
                totalTax += (amount * (rule.percentageRate() / 100.0)) + rule.fixedFee();
            }
        }
        return totalTax;
    }

    @Override
    public java.util.concurrent.CompletableFuture<Map<String, VaultTaxAPI.TaxRule>> getActiveTaxRulesAsync() {
        return java.util.concurrent.CompletableFuture.completedFuture(new java.util.HashMap<>(taxRules));
    }

    // ==========================================
    //           VaultCreditAPI Implementation
    // ==========================================
    private final Map<UUID, Map<String, VaultCreditAPI.CreditAccount>> creditAccounts = new ConcurrentHashMap<>();

    @Override
    public java.util.concurrent.CompletableFuture<VaultCreditAPI.CreditAccount> getCreditAccountAsync(OfflinePlayer player, String currency) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            UUID uuid = player != null ? player.getUniqueId() : UUID.randomUUID();
            String curr = currency == null ? "default" : currency;
            return creditAccounts.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(curr, c -> new VaultCreditAPI.CreditAccount(uuid, c, 500.0, 0.0, 700, false));
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<EconomyResponse> setOverdraftLimitAsync(OfflinePlayer player, String currency, double limit) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (player == null || limit < 0) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid arguments");
            }
            String curr = currency == null ? "default" : currency;
            var accMap = creditAccounts.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
            VaultCreditAPI.CreditAccount existing = accMap.getOrDefault(curr, new VaultCreditAPI.CreditAccount(player.getUniqueId(), curr, 500.0, 0.0, 700, false));
            accMap.put(curr, new VaultCreditAPI.CreditAccount(existing.playerUuid(), curr, limit, existing.currentUsedCredit(), existing.creditScore(), existing.isFrozen()));
            return new EconomyResponse(limit, limit, EconomyResponse.ResponseType.SUCCESS, "Overdraft limit updated to " + limit);
        }, asyncExecutor);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Integer> updateCreditScoreAsync(OfflinePlayer player) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (player == null) return 300;
            double bal = getBalance(player);
            int score = (int) Math.min(850, Math.max(300, 600 + (bal / 1000.0)));
            return score;
        }, asyncExecutor);
    }
}

