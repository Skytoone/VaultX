package net.milkbowl.vault.redis;

import org.bukkit.plugin.Plugin;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Infrastructure manager for Redis connection checks, Pub/Sub subscriber loops,
 * and heartbeat monitoring with exponential backoff.
 */
public class RedisConnectionManager {

    private static final int MAX_COOLDOWN_MS = 60000;
    private final Random random = new Random();

    private final Plugin plugin;
    private final JedisPool pool;
    private final String syncChannel;
    private final RedisPayloadEncryptor encryptor;
    private final ScheduledExecutorService redisExecutor;

    private JedisPubSub pubSub;
    private volatile boolean online = false;
    private boolean wasOnline = true;
    private volatile boolean closing = false;
    private int reconnectAttempts = 0;
    private org.bukkit.scheduler.BukkitTask subscriberTask;

    private Runnable onConnectionRestoredCallback;
    private Runnable onConnectionLostCallback;
    private Consumer<String> onMessageReceivedCallback;

    public RedisConnectionManager(Plugin plugin, JedisPool pool, String syncChannel, RedisPayloadEncryptor encryptor) {
        this.plugin = plugin;
        this.pool = pool;
        this.syncChannel = syncChannel;
        this.encryptor = encryptor;
        this.redisExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    public void setCallbacks(Runnable onConnectionRestored, Runnable onConnectionLost, Consumer<String> onMessageReceived) {
        this.onConnectionRestoredCallback = onConnectionRestored;
        this.onConnectionLostCallback = onConnectionLost;
        this.onMessageReceivedCallback = onMessageReceived;
    }

    public ScheduledExecutorService getExecutor() {
        return redisExecutor;
    }

    public boolean isOnline() {
        return online && pool != null && !pool.isClosed();
    }

    public boolean checkConnection() {
        if (pool == null || pool.isClosed()) return false;
        try (var jedis = pool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    public void start() {
        this.online = checkConnection();
        startSubscriber();
        startHeartbeat();
    }

    private void startSubscriber() {
        subscriberTask = net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            while (!closing && pool != null && !pool.isClosed()) {
                if (isOnline()) {
                    try (var jedis = pool.getResource()) {
                        pubSub = new JedisPubSub() {
                            @Override
                            public void onMessage(String channel, String message) {
                                if (channel.equals(syncChannel) && onMessageReceivedCallback != null) {
                                    onMessageReceivedCallback.accept(encryptor.decrypt(message));
                                }
                            }
                        };
                        jedis.subscribe(pubSub, syncChannel);
                    } catch (Exception ignored) {}
                }
                if (closing || pool == null || pool.isClosed()) break;
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private void startHeartbeat() {
        long heartbeatInterval = plugin.getConfig().getLong("redis.heartbeat-interval-seconds", 5L) * 20L;
        net.milkbowl.vault.util.FoliaScheduler.runLaterAsync(plugin, this::runHeartbeatCheck, heartbeatInterval);
    }

    private void runHeartbeatCheck() {
        if (closing) return;
        long baseCooldownMs = plugin.getConfig().getLong("redis.heartbeat-interval-seconds", 5L) * 1000L;
        if (baseCooldownMs < 1000L) baseCooldownMs = 1000L;

        boolean currentOnline = checkConnection();
        long nextDelayMs;

        if (currentOnline) {
            reconnectAttempts = 0;
            nextDelayMs = baseCooldownMs;

            if (!wasOnline) {
                plugin.getLogger().info("[VaultRedis] Redis connection re-established!");
                if (onConnectionRestoredCallback != null) {
                    onConnectionRestoredCallback.run();
                }
            }
        } else {
            if (wasOnline) {
                plugin.getLogger().warning("[VaultRedis] Redis connection lost! VaultX switched to Local Failover (SQLite) mode.");
                if (onConnectionLostCallback != null) {
                    onConnectionLostCallback.run();
                }
            }
            reconnectAttempts++;
            double backoff = baseCooldownMs * Math.pow(1.5, Math.min(reconnectAttempts, 8));
            double jitterRange = backoff * 0.2;
            double jitter = (random.nextDouble() * 2 * jitterRange) - jitterRange;

            nextDelayMs = (long) Math.min(MAX_COOLDOWN_MS, Math.max(baseCooldownMs, backoff + jitter));
        }

        wasOnline = currentOnline;
        online = currentOnline;

        long ticks = Math.max(20L, (nextDelayMs * 20L) / 1000L);

        if (!closing && pool != null && !pool.isClosed()) {
            net.milkbowl.vault.util.FoliaScheduler.runLaterAsync(plugin, this::runHeartbeatCheck, ticks);
        }
    }

    public void publishPayload(String payload) {
        if (!isOnline() || payload == null) return;
        try (var jedis = pool.getResource()) {
            jedis.publish(syncChannel, encryptor.encrypt(payload));
        } catch (Exception e) {
            plugin.getLogger().warning("[VaultRedis] Failed to publish payload: " + e.getMessage());
        }
    }

    public void close() {
        closing = true;
        if (subscriberTask != null) {
            try {
                subscriberTask.cancel();
            } catch (Exception ignored) {}
        }
        if (pubSub != null) {
            try {
                pubSub.unsubscribe();
            } catch (Exception ignored) {}
        }
        redisExecutor.shutdown();
        try {
            if (!redisExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                redisExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            redisExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
