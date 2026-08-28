package net.milkbowl.vault.economy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.milkbowl.vault.economy.events.VaultTransactionEvent;
import net.milkbowl.vault.economy.events.VaultTransactionEvent.TransactionType;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import net.milkbowl.vault.redis.VaultRedisManager;

/**
 * Enterprise-grade high performance decorator wrapper for Vault Economy
 * providers.
 * Implements ultra-fast O(1) in-memory caching for online players, Virtual
 * Threads,
 * Redis cross-server synchronization, and Multi-Currency support.
 */
@SuppressWarnings("deprecation")
public class OptimizedEconomy implements MultiCurrencyEconomy, VaultAsyncEconomy {

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
    private final java.util.concurrent.ExecutorService asyncExecutor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "VaultX-AsyncExecutor");
        t.setDaemon(true);
        return t;
    });

    // Cache entry structure for player balances
    private static class CacheEntry {
        final double balance;
        final long timestamp;
        CacheEntry(double balance, long timestamp) {
            this.balance = balance;
            this.timestamp = timestamp;
        }
    }

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

    private void triggerEventAsync(OfflinePlayer player, double amount, String currency, TransactionType type) {
        triggerEventAsync(player, null, amount, currency, type, null, -1.0);
    }

    private void triggerEventAsync(OfflinePlayer player, OfflinePlayer target, double amount, String currency, TransactionType type, String reason, double newBalance) {
        if (debugTransactions) {
            Bukkit.getLogger().info("[Vault Debug] Transaction: " + type + " " + amount + " (" + (currency == null ? "default" : currency) + ") for player " + (player != null ? player.getName() : "Unknown"));
        }
        String caller = findCallerPlugin();
        String curr = currency == null ? "default" : currency;
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            VaultTransactionEvent event = new VaultTransactionEvent(player, target, amount, curr, type, caller, reason, newBalance);
            Bukkit.getPluginManager().callEvent(event);
            if (net.milkbowl.vault.Vault.getFailoverManager() != null && player != null) {
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

    private long getEffectiveTtl() {
        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            return Math.max(onlineCacheTtlMs, 30000L);
        }
        return onlineCacheTtlMs;
    }
}

