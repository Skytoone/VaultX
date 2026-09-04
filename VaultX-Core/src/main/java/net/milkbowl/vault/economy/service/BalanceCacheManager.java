package net.milkbowl.vault.economy.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.redis.VaultPostgresManager;
import net.milkbowl.vault.redis.VaultRedisManager;
import net.milkbowl.vault.util.FoliaScheduler;

/**
 * Service encapsulating high-performance O(1) in-memory balance caching,
 * rate limiting state machine, and cleanup routines.
 */
public class BalanceCacheManager {

    public record CacheEntry(double balance, long timestamp) {
    }

    private final Plugin plugin;
    private final boolean useCache;
    private final boolean rateLimiterEnabled;
    private final int maxTps;
    private final int cooldownSeconds;
    private final long onlineCacheTtlMs;

    private final Map<UUID, Map<String, CacheEntry>> balanceCache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, CacheEntry>> offlineBalanceCache = new ConcurrentHashMap<>();
    private static final long OFFLINE_CACHE_TTL_MS = 10000L;
    private static final long NEGATIVE_CACHE_TTL_MS = 30000L;

    private final Map<UUID, Long> negativeAccountCache = new ConcurrentHashMap<>();

    private final Map<UUID, Long> rateLimitWindow = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> rateLimitCount = new ConcurrentHashMap<>();
    private final Map<UUID, Long> rateLimitBlock = new ConcurrentHashMap<>();

    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    private final BukkitTask cleanupTask;

    public BalanceCacheManager(Plugin plugin, boolean useCache, boolean rateLimiterEnabled,
                               int maxTps, int cooldownSeconds) {
        this.plugin = plugin;
        this.useCache = useCache;
        this.rateLimiterEnabled = rateLimiterEnabled;
        this.maxTps = maxTps;
        this.cooldownSeconds = cooldownSeconds;
        this.onlineCacheTtlMs = plugin != null ? plugin.getConfig().getLong("economy.cache-ttl-ms", 1000L) : 1000L;

        // Clean up rate limiting state and expired cache entries every 30 seconds
        this.cleanupTask = FoliaScheduler.runTimerAsync(plugin, () -> {
            long now = System.currentTimeMillis();

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

            rateLimitWindow.entrySet().removeIf(e -> (now - e.getValue()) > 1000L);
            rateLimitCount.entrySet().removeIf(e -> !rateLimitWindow.containsKey(e.getKey()));
            rateLimitBlock.entrySet().removeIf(e -> now > e.getValue());
        }, 300L, 300L);
    }

    public boolean isUseCache() {
        return useCache;
    }

    public long getOnlineCacheTtlMs() {
        return onlineCacheTtlMs;
    }

    public long getCacheHits() {
        return cacheHits.get();
    }

    public long getCacheMisses() {
        return cacheMisses.get();
    }

    public void recordHit() {
        cacheHits.incrementAndGet();
    }

    public void recordMiss() {
        cacheMisses.incrementAndGet();
    }

    public Map<UUID, Map<String, CacheEntry>> getBalanceCache() {
        return balanceCache;
    }

    public Map<UUID, Map<String, CacheEntry>> getOfflineBalanceCache() {
        return offlineBalanceCache;
    }

    public Map<UUID, Long> getNegativeAccountCache() {
        return negativeAccountCache;
    }

    public boolean isRateLimited(OfflinePlayer player) {
        if (!rateLimiterEnabled || player == null)
            return false;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (rateLimitBlock.containsKey(uuid)) {
            if (now < rateLimitBlock.get(uuid))
                return true;
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
                    plugin.getLogger().warning("[Vault Security] Player " + player.getName()
                            + " exceeded transaction rate limit! Blocked for " + cooldownSeconds + "s.");
                }
                if (net.milkbowl.vault.Vault.getFirewall() != null) {
                    net.milkbowl.vault.Vault.getFirewall().notifyRateLimit(player, count, maxTps, cooldownSeconds);
                }
                return true;
            }
        }
        return false;
    }

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

    public void purgePlayerCache(UUID uuid) {
        if (uuid == null)
            return;
        balanceCache.remove(uuid);
        offlineBalanceCache.remove(uuid);
        negativeAccountCache.remove(uuid);
        rateLimitWindow.remove(uuid);
        rateLimitCount.remove(uuid);
        rateLimitBlock.remove(uuid);
    }

    public void onPlayerJoin(Player player, Runnable warmCacheTask) {
        UUID uuid = player.getUniqueId();
        Map<String, CacheEntry> offlineBals = offlineBalanceCache.remove(uuid);
        if (offlineBals != null) {
            balanceCache.put(uuid, new ConcurrentHashMap<>(offlineBals));
        } else {
            FoliaScheduler.runAsync(plugin, warmCacheTask);
        }
    }

    public double getNativeDefaultBalance(UUID uuid) {
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null) {
            return redis.getCustomCurrencyBalance(uuid, "default");
        }
        return net.milkbowl.vault.Vault.getFailoverManager().getCustomCurrencyBalance(uuid, "default");
    }

    public void warmCache(UUID uuid, Economy delegate, List<String> supportedCurrencies) {
        if (!useCache)
            return;
        long now = System.currentTimeMillis();
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);

        double defaultBal = (delegate != null) ? delegate.getBalance(op) : getNativeDefaultBalance(uuid);
        balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put("default",
                new CacheEntry(defaultBal, now));
        offlineBalanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put("default",
                new CacheEntry(defaultBal, now));

        for (String currency : supportedCurrencies) {
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
                balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency.toLowerCase(),
                        new CacheEntry(bal, now));
                offlineBalanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency.toLowerCase(),
                        new CacheEntry(bal, now));
            }
        }
    }

    public void updateCache(OfflinePlayer player, String currency, double newBalance) {
        if (player == null)
            return;
        if (useCache) {
            String curr = currency == null ? "default" : currency.toLowerCase();
            long now = System.currentTimeMillis();
            if (player.isOnline()) {
                balanceCache.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>()).put(curr,
                        new CacheEntry(newBalance, now));
            } else {
                offlineBalanceCache.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>()).put(curr,
                        new CacheEntry(newBalance, now));
            }
        }
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null) {
            redis.publishBalanceUpdate(player.getUniqueId(), currency == null ? "default" : currency, newBalance);
        }
        VaultPostgresManager postgres = VaultPostgresManager.getInstance();
        if (postgres != null) {
            postgres.updateBalance(player.getUniqueId(), currency == null ? "default" : currency, newBalance);
        }
    }

    public void updateCacheFromRedis(UUID uuid, String currency, double newBalance) {
        if (useCache) {
            String curr = currency == null ? "default" : currency.toLowerCase();
            long now = System.currentTimeMillis();
            Player onlinePlayer = Bukkit.getPlayer(uuid);
            if (onlinePlayer != null) {
                balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(curr,
                        new CacheEntry(newBalance, now));
            } else {
                offlineBalanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(curr,
                        new CacheEntry(newBalance, now));
            }
        }
    }

    public void saveCustomCurrencyBalance(OfflinePlayer player, String currency, double balance) {
        String curr = currency == null ? "default" : currency.toLowerCase();
        VaultRedisManager redis = VaultRedisManager.getInstance();
        VaultPostgresManager postgres = VaultPostgresManager.getInstance();
        if (redis != null) {
            redis.setCustomCurrencyBalance(player.getUniqueId(), curr, balance);
        } else if (postgres != null) {
            postgres.updateBalance(player.getUniqueId(), curr, balance);
            final UUID uuid = player.getUniqueId();
            final String normalizedCurr = curr;
            final double newBal = balance;
            FoliaScheduler.runAsync(plugin, () -> {
                net.milkbowl.vault.Vault.getFailoverManager().saveCustomCurrencyBalance(uuid, normalizedCurr, newBal);
            });
        } else {
            final UUID uuid = player.getUniqueId();
            final String normalizedCurr = curr;
            final double newBal = balance;
            FoliaScheduler.runAsync(plugin, () -> {
                net.milkbowl.vault.Vault.getFailoverManager().saveCustomCurrencyBalance(uuid, normalizedCurr, newBal);
            });
        }
    }

    public long getEffectiveTtl() {
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            return Math.max(onlineCacheTtlMs, 30000L);
        }
        return onlineCacheTtlMs;
    }

    /**
     * Résout le solde depuis Redis ou le LocalFailoverManager.
     * Remplace le bloc Redis/Failover copié ~6× dans OptimizedEconomy.
     */
    public double resolveStorageBalance(UUID uuid, String currency) {
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null) {
            return redis.getCustomCurrencyBalance(uuid, currency);
        }
        return net.milkbowl.vault.Vault.getFailoverManager().getCustomCurrencyBalance(uuid, currency);
    }

    /**
     * Récupère le solde depuis le cache si valide, sinon appelle le fetcher puis met à jour le cache.
     * Unifie le pattern get-or-fetch répété 3× dans OptimizedEconomy (getBalance + getCurrencyBalance).
     *
     * @param uuid            UUID du joueur
     * @param isOnline        true si le joueur est connecté (utilise balanceCache, sinon offlineBalanceCache)
     * @param hasPlayedBefore false pour activer la vérification du negative-account cache
     * @param currency        devise (null → "default")
     * @param fetcher         fournisseur de la valeur réelle si cache absent/expiré
     */
    public double getOrFetchBalance(UUID uuid, boolean isOnline, boolean hasPlayedBefore,
                                    String currency, java.util.function.Supplier<Double> fetcher) {
        if (!useCache) return fetcher.get();
        long now = System.currentTimeMillis();
        String curr = (currency == null) ? "default" : currency.toLowerCase();

        if (isOnline) {
            Map<String, CacheEntry> playerBals = balanceCache.get(uuid);
            if (playerBals != null) {
                CacheEntry cached = playerBals.get(curr);
                if (cached != null && (now - cached.timestamp()) < getEffectiveTtl()) {
                    recordHit();
                    return cached.balance();
                }
            }
            recordMiss();
            double bal = fetcher.get();
            balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(curr, new CacheEntry(bal, now));
            return bal;
        } else {
            if (!hasPlayedBefore) {
                Long blockedUntil = negativeAccountCache.get(uuid);
                if (blockedUntil != null && now < blockedUntil) {
                    recordHit();
                    return 0.0;
                }
            }
            Map<String, CacheEntry> playerBals = offlineBalanceCache.get(uuid);
            if (playerBals != null) {
                CacheEntry entry = playerBals.get(curr);
                if (entry != null && (now - entry.timestamp()) < OFFLINE_CACHE_TTL_MS) {
                    recordHit();
                    return entry.balance();
                }
            }
            recordMiss();
            double bal = fetcher.get();
            if (bal == 0.0 && !hasPlayedBefore) {
                negativeAccountCache.put(uuid, now + NEGATIVE_CACHE_TTL_MS);
            }
            offlineBalanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(curr, new CacheEntry(bal, now));
            return bal;
        }
    }

    /**
     * Retourne une Map UUID → (devise → solde) fusionnant les caches online et offline.
     * Les entrées online ont priorité sur les entrées offline pour le même joueur/devise.
     * Utiliser dans createSnapshotAsync pour ne pas exposer les Maps internes.
     */
    public Map<UUID, Map<String, Double>> collectAllCachedBalances() {
        Map<UUID, Map<String, Double>> result = new HashMap<>();
        // Offline d’abord (priorité basse)
        for (Map.Entry<UUID, Map<String, CacheEntry>> entry : offlineBalanceCache.entrySet()) {
            Map<String, Double> playerBals = new HashMap<>();
            for (Map.Entry<String, CacheEntry> cEntry : entry.getValue().entrySet()) {
                playerBals.put(cEntry.getKey().toLowerCase(), cEntry.getValue().balance());
            }
            result.put(entry.getKey(), playerBals);
        }
        // Online ensuite (priorité haute, écrase si conflit)
        for (Map.Entry<UUID, Map<String, CacheEntry>> entry : balanceCache.entrySet()) {
            Map<String, Double> playerBals = result.computeIfAbsent(entry.getKey(), k -> new HashMap<>());
            for (Map.Entry<String, CacheEntry> cEntry : entry.getValue().entrySet()) {
                playerBals.put(cEntry.getKey().toLowerCase(), cEntry.getValue().balance());
            }
        }
        return result;
    }

    public void shutdown() {
        if (cleanupTask != null) {
            try {
                cleanupTask.cancel();
            } catch (Exception ignored) {
            }
        }
        balanceCache.clear();
        offlineBalanceCache.clear();
        negativeAccountCache.clear();
        rateLimitWindow.clear();
        rateLimitCount.clear();
        rateLimitBlock.clear();
    }
}
