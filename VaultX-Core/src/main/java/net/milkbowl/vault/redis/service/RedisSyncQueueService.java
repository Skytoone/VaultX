package net.milkbowl.vault.redis.service;

import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.redis.RedisPayloadEncryptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles debounced balance publishing, batch sync queueing, and shutdown flush tasks.
 */
public class RedisSyncQueueService {

    private final JedisPool pool;
    private final String serverId;
    private final String syncChannel;
    private final RedisPayloadEncryptor encryptor;
    private final LocalFailoverManager failoverManager;
    private final ScheduledExecutorService redisExecutor;
    private final RedisLeaderboardService leaderboardService;

    private final Map<String, ScheduledFuture<?>> pendingPublishes = new ConcurrentHashMap<>();
    private final Map<String, Double> pendingBalances = new ConcurrentHashMap<>();

    public RedisSyncQueueService(
            JedisPool pool,
            String serverId,
            String syncChannel,
            RedisPayloadEncryptor encryptor,
            LocalFailoverManager failoverManager,
            ScheduledExecutorService redisExecutor,
            RedisLeaderboardService leaderboardService) {
        this.pool = pool;
        this.serverId = serverId;
        this.syncChannel = syncChannel;
        this.encryptor = encryptor;
        this.failoverManager = failoverManager;
        this.redisExecutor = redisExecutor;
        this.leaderboardService = leaderboardService;
    }

    public void queueBalancePublish(UUID playerUuid, String currency, double balance, boolean isOnline) {
        if (playerUuid == null) return;
        String curr = (currency == null) ? "default" : currency;
        String key = playerUuid.toString() + ":" + curr;
        pendingBalances.put(key, balance);

        ScheduledFuture<?> existing = pendingPublishes.get(key);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }

        ScheduledFuture<?> future = redisExecutor.schedule(() -> {
            pendingPublishes.remove(key);
            Double latestBal = pendingBalances.remove(key);
            if (latestBal == null) return;

            if (isOnline && pool != null && !pool.isClosed()) {
                try (Jedis jedis = pool.getResource()) {
                    long timestamp = System.currentTimeMillis();
                    jedis.hset("vaultx:balances:" + playerUuid.toString(), curr, String.valueOf(latestBal));
                    jedis.hset("vaultx:timestamps:" + playerUuid.toString(), curr, String.valueOf(timestamp));
                    leaderboardService.updateLeaderboardAndStats(jedis, curr, playerUuid.toString(), latestBal);
                    String payload = serverId + ":" + playerUuid.toString() + ":" + curr + ":" + latestBal + ":" + timestamp;
                    jedis.publish(syncChannel, encryptor.encrypt(payload));
                } catch (Exception e) {
                    if (failoverManager != null) {
                        failoverManager.queueBalanceSync(playerUuid, curr, latestBal);
                    }
                }
            } else if (failoverManager != null) {
                failoverManager.queueBalanceSync(playerUuid, curr, latestBal);
            }
        }, 300, TimeUnit.MILLISECONDS);

        pendingPublishes.put(key, future);
    }

    public void flushAndClear(boolean isOnline) {
        for (ScheduledFuture<?> future : pendingPublishes.values()) {
            if (future != null) {
                try {
                    future.cancel(false);
                } catch (Exception ignored) {}
            }
        }
        pendingPublishes.clear();

        if (isOnline && pool != null && !pool.isClosed() && !pendingBalances.isEmpty()) {
            try (Jedis jedis = pool.getResource()) {
                for (Map.Entry<String, Double> entry : pendingBalances.entrySet()) {
                    String[] parts = entry.getKey().split(":");
                    if (parts.length == 2) {
                        try {
                            UUID playerUuid = UUID.fromString(parts[0]);
                            String curr = parts[1];
                            double balance = entry.getValue();
                            long timestamp = System.currentTimeMillis();
                            jedis.hset("vaultx:balances:" + playerUuid.toString(), curr, String.valueOf(balance));
                            jedis.hset("vaultx:timestamps:" + playerUuid.toString(), curr, String.valueOf(timestamp));
                            leaderboardService.updateLeaderboardAndStats(jedis, curr, playerUuid.toString(), balance);
                            String payload = serverId + ":" + playerUuid.toString() + ":" + curr + ":" + balance + ":" + timestamp;
                            jedis.publish(syncChannel, encryptor.encrypt(payload));
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                if (failoverManager != null) {
                    for (Map.Entry<String, Double> entry : pendingBalances.entrySet()) {
                        String[] parts = entry.getKey().split(":");
                        if (parts.length == 2) {
                            try {
                                failoverManager.queueBalanceSync(UUID.fromString(parts[0]), parts[1], entry.getValue());
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } else if (failoverManager != null) {
            for (Map.Entry<String, Double> entry : pendingBalances.entrySet()) {
                String[] parts = entry.getKey().split(":");
                if (parts.length == 2) {
                    try {
                        failoverManager.queueBalanceSync(UUID.fromString(parts[0]), parts[1], entry.getValue());
                    } catch (Exception ignored) {}
                }
            }
        }
        pendingBalances.clear();
    }
}
