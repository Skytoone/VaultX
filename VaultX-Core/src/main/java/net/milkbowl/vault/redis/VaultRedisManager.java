package net.milkbowl.vault.redis;

import net.milkbowl.vault.economy.OptimizedEconomy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VaultRedisManager {

    public static class LeaderboardEntry {
        public final String name;
        public final double balance;

        public LeaderboardEntry(String name, double balance) {
            this.name = name;
            this.balance = balance;
        }
    }

    private static VaultRedisManager instance;
    private final JedisPool pool;
    private final String serverId;
    private final Plugin plugin;
    private final String syncChannel;
    private final LocalFailoverManager failoverManager;
    private final java.util.concurrent.ScheduledExecutorService redisExecutor;

    private final Map<String, List<LeaderboardEntry>> leaderboardCaches = new ConcurrentHashMap<>();
    private org.bukkit.scheduler.BukkitTask leaderboardTask;

    private final Map<String, java.util.concurrent.ScheduledFuture<?>> pendingPublishes = new ConcurrentHashMap<>();
    private final Map<String, Double> pendingBalances = new ConcurrentHashMap<>();

    private static final String UPDATE_BALANCE_STATS_LUA = "local oldScore = redis.call('zscore', KEYS[1], ARGV[1])\n" +
            "redis.call('zadd', KEYS[1], ARGV[2], ARGV[1])\n" +
            "local diff = tonumber(ARGV[2])\n" +
            "if oldScore then\n" +
            "    diff = tonumber(ARGV[2]) - tonumber(oldScore)\n" +
            "else\n" +
            "    redis.call('incr', KEYS[3])\n" +
            "end\n" +
            "redis.call('incrbyfloat', KEYS[2], diff)\n" +
            "return 1";

    private String updateScriptSha1 = null;

    private void updateLeaderboardAndStats(redis.clients.jedis.Jedis jedis, String curr, String member, double score) {
        String leaderboardKey = "vaultx:leaderboard:" + curr;
        String totalMoneyKey = "vaultx:stats:total_money:" + curr;
        String accountsCountKey = "vaultx:stats:accounts_count:" + curr;

        List<String> keys = java.util.Arrays.asList(leaderboardKey, totalMoneyKey, accountsCountKey);
        List<String> args = java.util.Arrays.asList(member, String.valueOf(score));

        try {
            if (updateScriptSha1 == null) {
                updateScriptSha1 = jedis.scriptLoad(UPDATE_BALANCE_STATS_LUA);
            }
            jedis.evalsha(updateScriptSha1, keys, args);
        } catch (Exception e) {
            try {
                updateScriptSha1 = jedis.scriptLoad(UPDATE_BALANCE_STATS_LUA);
            } catch (Exception ignored) {}
            jedis.eval(UPDATE_BALANCE_STATS_LUA, keys, args);
        }
    }

    private JedisPubSub pubSub;
    private boolean wasOnline = true;
    private volatile boolean online = false;
    private int reconnectAttempts = 0;
    private static final int MAX_COOLDOWN_MS = 60000;
    private final java.util.Random random = new java.util.Random();

    public VaultRedisManager(Plugin plugin, String host, int port, String password, String serverId, String channel) {
        this.plugin = plugin;
        this.serverId = serverId;
        this.syncChannel = channel;
        this.failoverManager = net.milkbowl.vault.Vault.getFailoverManager();
        this.redisExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

        int maxConnections = plugin.getConfig().getInt("redis.max-connections", 16);
        int timeoutMs = plugin.getConfig().getInt("redis.connection-timeout-ms", 250);

        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(maxConnections); // Configurable
        config.setMaxIdle(maxConnections);
        config.setMinIdle(Math.max(2, maxConnections / 4));
        config.setBlockWhenExhausted(true);
        config.setMaxWait(java.time.Duration.ofMillis(timeoutMs));

        if (password != null && !password.isEmpty()) {
            this.pool = new JedisPool(config, host, port, timeoutMs, password);
        } else {
            this.pool = new JedisPool(config, host, port, timeoutMs);
        }

        instance = this;
        this.online = checkConnection();
        startSubscriber();
        startHeartbeat();
        startLeaderboardUpdater();

        if (this.online) {
            syncFrozenPlayers();
        }

        plugin.getLogger().info("[VaultRedis] Redis synchronization enabled for server: " + serverId);
    }

    public static VaultRedisManager getInstance() {
        return instance;
    }

    public LocalFailoverManager getFailoverManager() {
        return failoverManager;
    }

    public redis.clients.jedis.JedisPool getPool() {
        return pool;
    }

    private volatile boolean closing = false;
    private org.bukkit.scheduler.BukkitTask subscriberTask;

    public void close() {
        closing = true;
        stopLeaderboardUpdater();
        if (subscriberTask != null) {
            try {
                subscriberTask.cancel();
            } catch (Exception ignored) {}
        }
        if (pubSub != null) {
            try {
                pubSub.unsubscribe();
            } catch (Exception e) {
                // Ignore
            }
        }

        // Cancel all pending future tasks
        for (java.util.concurrent.ScheduledFuture<?> future : pendingPublishes.values()) {
            if (future != null) {
                try {
                    future.cancel(false);
                } catch (Exception ignored) {}
            }
        }

        // Flush all pending balances synchronously before shutting down
        for (Map.Entry<String, Double> entry : pendingBalances.entrySet()) {
            String[] parts = entry.getKey().split(":");
            if (parts.length == 2) {
                try {
                    UUID playerUuid = UUID.fromString(parts[0]);
                    String curr = parts[1];
                    double balance = entry.getValue();
                    long timestamp = System.currentTimeMillis();
                    if (isOnline()) {
                        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
                            jedis.hset("vaultx:balances:" + playerUuid.toString(), curr, String.valueOf(balance));
                            jedis.hset("vaultx:timestamps:" + playerUuid.toString(), curr, String.valueOf(timestamp));
                            updateLeaderboardAndStats(jedis, curr, playerUuid.toString(), balance);
                            String payload = serverId + ":" + playerUuid.toString() + ":" + curr + ":" + balance + ":"
                                    + timestamp;
                            jedis.publish(syncChannel, payload);
                        }
                    } else {
                        failoverManager.queueBalanceSync(playerUuid, curr, balance);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        pendingBalances.clear();
        pendingPublishes.clear();

        // Shut down the executor
        redisExecutor.shutdown();
        try {
            if (!redisExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                redisExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            redisExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (pool != null) {
            try {
                pool.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        instance = null;
    }

    private void startSubscriber() {
        subscriberTask = net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, new Runnable() {
            @Override
            public void run() {
                while (!closing && pool != null && !pool.isClosed()) {
                    if (isOnline()) {
                        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
                            pubSub = new JedisPubSub() {
                                @Override
                                public void onMessage(String channel, String message) {
                                    if (channel.equals(syncChannel)) {
                                        handleSyncMessage(message);
                                    }
                                }
                            };
                            jedis.subscribe(pubSub, syncChannel);
                        } catch (Exception e) {
                            // Suppress excessive logs during reconnect attempts
                        }
                    }
                    if (closing || pool == null || pool.isClosed()) break;
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
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
        if (baseCooldownMs < 1000L)
            baseCooldownMs = 1000L; // Safety floor

        boolean currentOnline = checkConnection();
        long nextDelayMs;

        if (currentOnline) {
            reconnectAttempts = 0;
            nextDelayMs = baseCooldownMs;

            if (!wasOnline) {
                plugin.getLogger()
                        .info("[VaultRedis] Redis connection re-established! Syncing offline modifications.");
                failoverManager.processQueue(VaultRedisManager.this);
                syncLocalBanksToRedis();
                syncFrozenPlayers();
            }
        } else {
            if (wasOnline) {
                plugin.getLogger().warning(
                        "[VaultRedis] Redis connection lost! VaultX switched to Local Failover (SQLite) mode.");
            }
            reconnectAttempts++;
            // Exponential backoff: base * 1.5^attempts, max out around 8 attempts
            double backoff = baseCooldownMs * Math.pow(1.5, Math.min(reconnectAttempts, 8));
            // Add Jitter (+/- 20% of current backoff)
            double jitterRange = backoff * 0.2;
            double jitter = (random.nextDouble() * 2 * jitterRange) - jitterRange;

            nextDelayMs = (long) Math.min(MAX_COOLDOWN_MS, Math.max(baseCooldownMs, backoff + jitter));
        }

        wasOnline = currentOnline;
        online = currentOnline;

        // Convert ms to ticks (20 ticks per second)
        long ticks = Math.max(20L, (nextDelayMs * 20L) / 1000L);

        if (!closing && pool != null && !pool.isClosed()) {
            net.milkbowl.vault.util.FoliaScheduler.runLaterAsync(plugin, this::runHeartbeatCheck, ticks);
        }
    }

    private void syncLocalBanksToRedis() {
        try {
            java.util.Map<String, Double> localBanks = failoverManager.loadAllBanks();
            for (java.util.Map.Entry<String, Double> entry : localBanks.entrySet()) {
                setBankBalance(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            // Silently handle error
        }
    }

    private void handleSyncMessage(String message) {
        // Format: serverId:uuid:currency:balance OR serverId:BANK:bankName:balance
        String[] parts = message.split(":");
        if (parts.length >= 4) {
            String originServer = parts[0];
            if (originServer.equals(this.serverId))
                return; // Ignore own messages

            if (parts[1].equals("BANK_MEMBER_UPDATE")) {
                try {
                    String bankName = parts[2];
                    UUID uuid = UUID.fromString(parts[3]);
                    String role = parts[4];
                    if (role.equals("REMOVE")) {
                        failoverManager.removeBankMember(bankName, uuid);
                    } else {
                        failoverManager.addBankMember(bankName, uuid, role);
                    }
                } catch (Exception e) {
                }
                return;
            }

            if (parts[1].equals("BANK")) {
                try {
                    String bankName = parts[2];
                    double balance = Double.parseDouble(parts[3]);
                    org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp = Bukkit
                            .getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
                    if (rsp != null && rsp.getProvider() instanceof OptimizedEconomy) {
                        ((OptimizedEconomy) rsp.getProvider()).updateBankCacheFromRedis(bankName, balance);
                    }
                } catch (Exception e) {
                }
                return;
            }

            if (parts[1].equals("FREEZE")) {
                try {
                    final UUID uuid = UUID.fromString(parts[2]);
                    final String reason = parts.length > 3 ? parts[3] : "Synchronized from Redis";
                    net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, new Runnable() {
                        @Override
                        public void run() {
                            if (net.milkbowl.vault.Vault.getFirewall() != null) {
                                net.milkbowl.vault.Vault.getFirewall().freezePlayerLocal(uuid, reason);
                            }
                        }
                    });
                } catch (Exception e) {
                }
                return;
            }

            if (parts[1].equals("UNFREEZE")) {
                try {
                    final UUID uuid = UUID.fromString(parts[2]);
                    net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, new Runnable() {
                        @Override
                        public void run() {
                            if (net.milkbowl.vault.Vault.getFirewall() != null) {
                                net.milkbowl.vault.Vault.getFirewall().unfreezePlayerLocal(uuid);
                            }
                        }
                    });
                } catch (Exception e) {
                }
                return;
            }

            try {
                UUID uuid = UUID.fromString(parts[1]);
                String currency = parts[2];
                double balance = Double.parseDouble(parts[3]);
                long timestamp = parts.length > 4 ? Long.parseLong(parts[4]) : System.currentTimeMillis();

                long localTimestamp = failoverManager.getCustomCurrencyTimestamp(uuid, currency);
                if (timestamp >= localTimestamp) {
                    if (currency.equalsIgnoreCase("default")) {
                        failoverManager.saveCustomCurrencyBalance(uuid, "default", balance, timestamp);
                        org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp = Bukkit
                                .getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
                        if (rsp != null && rsp.getProvider() instanceof OptimizedEconomy) {
                            ((OptimizedEconomy) rsp.getProvider()).updateCacheFromRedis(uuid, currency, balance);
                        }
                        failoverManager.updateDelegateBalance(uuid, balance);
                    } else {
                        failoverManager.saveCustomCurrencyBalance(uuid, currency, balance, timestamp);
                        org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp = Bukkit
                                .getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
                        if (rsp != null && rsp.getProvider() instanceof OptimizedEconomy) {
                            ((OptimizedEconomy) rsp.getProvider()).updateCacheFromRedis(uuid, currency, balance);
                        }
                    }
                }
            } catch (Exception e) {
                // Invalid format
            }
        }
    }

    public void publishBalanceUpdate(final UUID playerUuid, final String currency, final double newBalance) {
        final String curr = (currency == null ? "default" : currency).toLowerCase();
        final String key = playerUuid.toString() + ":" + curr;

        pendingBalances.put(key, newBalance);

        // Cancel existing pending task if any
        java.util.concurrent.ScheduledFuture<?> existing = pendingPublishes.remove(key);
        if (existing != null) {
            existing.cancel(false);
        }

        // Schedule new task with 200ms delay
        java.util.concurrent.ScheduledFuture<?> task = redisExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                pendingPublishes.remove(key);
                Double balanceObj = pendingBalances.remove(key);
                if (balanceObj == null)
                    return;

                final double balance = balanceObj;
                final long timestamp = System.currentTimeMillis();

                if (!isOnline()) {
                    failoverManager.queueBalanceSync(playerUuid, curr, balance);
                    return;
                }
                try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
                    jedis.hset("vaultx:balances:" + playerUuid.toString(), curr, String.valueOf(balance));
                    jedis.hset("vaultx:timestamps:" + playerUuid.toString(), curr, String.valueOf(timestamp));
                    updateLeaderboardAndStats(jedis, curr, playerUuid.toString(), balance);
                    String payload = serverId + ":" + playerUuid.toString() + ":" + curr + ":" + balance + ":"
                            + timestamp;
                    jedis.publish(syncChannel, payload);
                } catch (Exception e) {
                    plugin.getLogger()
                            .warning("[VaultRedis] Failed to publish balance update to Redis. Queueing locally: "
                                    + e.getMessage());
                    failoverManager.queueBalanceSync(playerUuid, curr, balance);
                }
            }
        }, 200, java.util.concurrent.TimeUnit.MILLISECONDS);

        pendingPublishes.put(key, task);
    }

    public void publishBankMemberUpdate(final String bankName, final UUID uuid, final String role) {
        if (!isOnline())
            return;
        redisExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
                    String payload = serverId + ":BANK_MEMBER_UPDATE:" + bankName.toLowerCase() + ":" + uuid.toString()
                            + ":" + role.toUpperCase();
                    jedis.publish(syncChannel, payload);
                } catch (Exception e) {
                    plugin.getLogger().warning("[VaultRedis] Failed to publish bank member update: " + e.getMessage());
                }
            }
        });
    }

    public double getBankBalance(String bankName) {
        if (!isOnline()) {
            return failoverManager.getBankBalance(bankName);
        }
        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            String val = jedis.hget("vaultx:banks", bankName);
            return val == null ? 0.0 : Double.parseDouble(val);
        } catch (Exception e) {
            return failoverManager.getBankBalance(bankName);
        }
    }

    public void setBankBalance(final String bankName, final double balance) {
        redisExecutor.submit(new Runnable() {
            @Override
            public void run() {
                // Save locally to SQL first (run asynchronously to avoid main thread disk
                // bottleneck)
                failoverManager.saveBankBalance(bankName, balance);

                if (!isOnline()) {
                    return;
                }

                try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
                    jedis.hset("vaultx:banks", bankName, String.valueOf(balance));
                    String payload = serverId + ":BANK:" + bankName + ":" + balance;
                    jedis.publish(syncChannel, payload);
                } catch (Exception e) {
                    plugin.getLogger().warning("[VaultRedis] Failed to sync bank update: " + e.getMessage());
                }
            }
        });
    }

    public boolean isOnline() {
        return online && pool != null && !pool.isClosed();
    }

    public boolean checkConnection() {
        if (pool == null || pool.isClosed())
            return false;
        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    public boolean acquireLock(String lockKey, String value, int expireTimeMs) {
        if (!isOnline())
            return true; // Standalone failover bypasses lock
        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            redis.clients.jedis.params.SetParams params = redis.clients.jedis.params.SetParams.setParams().nx()
                    .px(expireTimeMs);
            String result = jedis.set("vaultx:lock:" + lockKey, value, params);
            return "OK".equalsIgnoreCase(result);
        } catch (Exception e) {
            return true; // Connection down, bypass lock to allow offline play
        }
    }

    public boolean releaseLock(String lockKey, String value) {
        if (!isOnline())
            return true;
        String luaScript = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) " +
                "else " +
                "return 0 " +
                "end";
        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            Object result = jedis.eval(luaScript,
                    java.util.Collections.singletonList("vaultx:lock:" + lockKey),
                    java.util.Collections.singletonList(value));
            return Long.valueOf(1).equals(result);
        } catch (Exception e) {
            return true;
        }
    }

    public void freezePlayerInRedis(final UUID uuid, final String reason) {
        if (!isOnline())
            return;
        redisExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
                    jedis.sadd("vaultx:frozen_players", uuid.toString());
                    String payload = serverId + ":FREEZE:" + uuid.toString() + ":" + reason;
                    jedis.publish(syncChannel, payload);
                } catch (Exception e) {
                    plugin.getLogger().warning("[VaultRedis] Failed to publish freeze update: " + e.getMessage());
                }
            }
        });
    }

    public void unfreezePlayerInRedis(final UUID uuid) {
        if (!isOnline())
            return;
        redisExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
                    jedis.srem("vaultx:frozen_players", uuid.toString());
                    String payload = serverId + ":UNFREEZE:" + uuid.toString();
                    jedis.publish(syncChannel, payload);
                } catch (Exception e) {
                    plugin.getLogger().warning("[VaultRedis] Failed to publish unfreeze update: " + e.getMessage());
                }
            }
        });
    }

    private void syncFrozenPlayers() {
        if (!isOnline())
            return;
        redisExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
                    java.util.Set<String> redisFrozen = jedis.smembers("vaultx:frozen_players");
                    if (redisFrozen != null) {
                        for (String s : redisFrozen) {
                            try {
                                final UUID uuid = UUID.fromString(s);
                                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, new Runnable() {
                                    @Override
                                    public void run() {
                                        if (net.milkbowl.vault.Vault.getFirewall() != null) {
                                            net.milkbowl.vault.Vault.getFirewall().freezePlayerLocal(uuid,
                                                    "Synchronized from Redis");
                                        }
                                    }
                                });
                            } catch (Exception e) {
                            }
                        }
                    }
                    if (net.milkbowl.vault.Vault.getFirewall() != null) {
                        java.util.Set<UUID> localFrozen = net.milkbowl.vault.Vault.getFirewall()
                                .getLocalFrozenPlayers();
                        for (UUID uuid : localFrozen) {
                            jedis.sadd("vaultx:frozen_players", uuid.toString());
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[VaultRedis] Failed to sync frozen players: " + e.getMessage());
                }
            }
        });
    }

    public double getCustomCurrencyBalance(UUID uuid, String currency) {
        if (!isOnline()) {
            return failoverManager.getCustomCurrencyBalance(uuid, currency);
        }
        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            String val = jedis.hget("vaultx:balances:" + uuid.toString(), currency.toLowerCase());
            if (val != null) {
                return Double.parseDouble(val);
            }
        } catch (Exception e) {
            // Fallback
        }
        return failoverManager.getCustomCurrencyBalance(uuid, currency);
    }

    public void setCustomCurrencyBalance(final UUID uuid, final String currency, final double balance) {
        final long timestamp = System.currentTimeMillis();
        redisExecutor.submit(new Runnable() {
            @Override
            public void run() {
                failoverManager.saveCustomCurrencyBalance(uuid, currency, balance, timestamp);
                if (!isOnline())
                    return;
                try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
                    String curr = (currency == null ? "default" : currency).toLowerCase();
                    jedis.hset("vaultx:balances:" + uuid.toString(), curr, String.valueOf(balance));
                    jedis.hset("vaultx:timestamps:" + uuid.toString(), curr, String.valueOf(timestamp));
                    updateLeaderboardAndStats(jedis, curr, uuid.toString(), balance);
                    String payload = serverId + ":" + uuid.toString() + ":" + curr + ":" + balance + ":" + timestamp;
                    jedis.publish(syncChannel, payload);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to sync custom currency update: " + e.getMessage());
                }
            }
        });
    }

    private void startLeaderboardUpdater() {
        int intervalTicks = plugin.getConfig().getInt("advanced.global-rich-list.update-interval-minutes", 5) * 1200;
        leaderboardTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, new Runnable() {
            @Override
            public void run() {
                updateAllLeaderboards();
            }
        }, 100L, intervalTicks);
    }

    public void stopLeaderboardUpdater() {
        if (leaderboardTask != null) {
            leaderboardTask.cancel();
            leaderboardTask = null;
        }
    }

    private void updateAllLeaderboards() {
        if (!isOnline())
            return;

        List<String> currencies = new ArrayList<>();
        currencies.add("default");
        org.bukkit.configuration.ConfigurationSection section = plugin.getConfig()
                .getConfigurationSection("currency-exchange.rates");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                currencies.add(key.toLowerCase());
            }
        }

        int maxPlayers = plugin.getConfig().getInt("advanced.global-rich-list.max-tracked-players", 100);

        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            for (String currency : currencies) {
                String key = "vaultx:leaderboard:" + currency;
                List<redis.clients.jedis.resps.Tuple> range = jedis.zrevrangeWithScores(key, 0, maxPlayers - 1);
                List<LeaderboardEntry> entries = new ArrayList<>();
                for (redis.clients.jedis.resps.Tuple tuple : range) {
                    try {
                        UUID uuid = UUID.fromString(tuple.getElement());
                        double balance = tuple.getScore();
                        String name = net.milkbowl.vault.util.UUIDCache.getName(uuid);
                        if (name == null) {
                            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                            name = op != null ? op.getName() : "Unknown";
                        }
                        entries.add(new LeaderboardEntry(name, balance));
                    } catch (Exception ex) {
                        // Ignore individual parse issues
                    }
                }
                leaderboardCaches.put(currency, java.util.Collections.unmodifiableList(entries));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[VaultRedis] Failed to update leaderboards from Redis: " + e.getMessage());
        }
    }

    public List<LeaderboardEntry> getLeaderboard(String currency) {
        return leaderboardCaches.getOrDefault(currency.toLowerCase(), java.util.Collections.emptyList());
    }

    public long getCustomCurrencyTimestamp(UUID uuid, String currency) {
        if (!isOnline()) {
            return failoverManager.getCustomCurrencyTimestamp(uuid, currency);
        }
        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            String curr = (currency == null ? "default" : currency).toLowerCase();
            String val = jedis.hget("vaultx:timestamps:" + uuid.toString(), curr);
            if (val != null) {
                return Long.parseLong(val);
            }
        } catch (Exception e) {
            // ignore
        }
        return failoverManager.getCustomCurrencyTimestamp(uuid, currency);
    }

    public void publishBalanceUpdateDirect(UUID uuid, String currency, double balance, long timestamp) {
        if (!isOnline()) {
            failoverManager.queueBalanceSync(uuid, currency, balance);
            return;
        }
        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            String curr = (currency == null ? "default" : currency).toLowerCase();
            jedis.hset("vaultx:balances:" + uuid.toString(), curr, String.valueOf(balance));
            jedis.hset("vaultx:timestamps:" + uuid.toString(), curr, String.valueOf(timestamp));
            updateLeaderboardAndStats(jedis, curr, uuid.toString(), balance);
            String payload = serverId + ":" + uuid.toString() + ":" + curr + ":" + balance + ":" + timestamp;
            jedis.publish(syncChannel, payload);
        } catch (Exception e) {
            plugin.getLogger().warning("[VaultRedis] Failed to direct publish balance update: " + e.getMessage());
            failoverManager.queueBalanceSync(uuid, currency, balance);
        }
    }

    public double getCustomCurrencyBalanceDirect(UUID uuid, String currency) {
        if (!isOnline()) {
            return failoverManager.getCustomCurrencyBalance(uuid, currency);
        }
        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            String curr = (currency == null ? "default" : currency).toLowerCase();
            String val = jedis.hget("vaultx:balances:" + uuid.toString(), curr);
            if (val != null) {
                return Double.parseDouble(val);
            }
        } catch (Exception e) {
            // ignore
        }
        return failoverManager.getCustomCurrencyBalance(uuid, currency);
    }

    public static class CurrencyStats {
        public final double totalMoney;
        public final long accountsCount;
        public final double averageBalance;

        public CurrencyStats(double totalMoney, long accountsCount, double averageBalance) {
            this.totalMoney = totalMoney;
            this.accountsCount = accountsCount;
            this.averageBalance = averageBalance;
        }
    }

    public CurrencyStats getGlobalCurrencyStats(String currency) {
        if (!isOnline()) {
            return null;
        }
        String curr = currency.toLowerCase();
        String totalMoneyKey = "vaultx:stats:total_money:" + curr;
        String accountsCountKey = "vaultx:stats:accounts_count:" + curr;

        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            String countStr = jedis.get(accountsCountKey);
            String totalStr = jedis.get(totalMoneyKey);

            if (countStr != null && totalStr != null) {
                long count = Long.parseLong(countStr);
                double total = Double.parseDouble(totalStr);
                return new CurrencyStats(total, count, count == 0 ? 0.0 : total / count);
            }

            // Fallback (auto-healing / first time seeding)
            String key = "vaultx:leaderboard:" + curr;
            long count = jedis.zcard(key);
            double total = 0.0;
            if (count > 0) {
                List<redis.clients.jedis.resps.Tuple> range = jedis.zrangeWithScores(key, 0, -1);
                for (redis.clients.jedis.resps.Tuple tuple : range) {
                    total += tuple.getScore();
                }
            }

            jedis.set(accountsCountKey, String.valueOf(count));
            jedis.set(totalMoneyKey, String.valueOf(total));

            return new CurrencyStats(total, count, count == 0 ? 0.0 : total / count);
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[VaultRedis] Failed to get global stats for currency " + currency + ": " + e.getMessage());
            return null;
        }
    }

    public void publishBalanceUpdatesBulk(
            java.util.List<net.milkbowl.vault.redis.LocalFailoverManager.CustomBalanceRecord> records, long timestamp) {
        if (!isOnline()) {
            for (net.milkbowl.vault.redis.LocalFailoverManager.CustomBalanceRecord rec : records) {
                failoverManager.queueBalanceSync(rec.uuid, rec.currency, rec.balance);
            }
            return;
        }
        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            redis.clients.jedis.Pipeline pipeline = jedis.pipelined();
            for (net.milkbowl.vault.redis.LocalFailoverManager.CustomBalanceRecord rec : records) {
                String curr = (rec.currency == null ? "default" : rec.currency).toLowerCase();
                String uuidStr = rec.uuid.toString();
                pipeline.hset("vaultx:balances:" + uuidStr, curr, String.valueOf(rec.balance));
                pipeline.hset("vaultx:timestamps:" + uuidStr, curr, String.valueOf(timestamp));

                String leaderboardKey = "vaultx:leaderboard:" + curr;
                String totalMoneyKey = "vaultx:stats:total_money:" + curr;
                String accountsCountKey = "vaultx:stats:accounts_count:" + curr;
                pipeline.eval(UPDATE_BALANCE_STATS_LUA,
                        java.util.Arrays.asList(leaderboardKey, totalMoneyKey, accountsCountKey),
                        java.util.Arrays.asList(uuidStr, String.valueOf(rec.balance)));

                String payload = serverId + ":" + uuidStr + ":" + curr + ":" + rec.balance + ":" + timestamp;
                pipeline.publish(syncChannel, payload);
            }
            pipeline.sync();
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[VaultRedis] Failed to bulk publish balance updates. Queueing locally: " + e.getMessage());
            for (net.milkbowl.vault.redis.LocalFailoverManager.CustomBalanceRecord rec : records) {
                failoverManager.queueBalanceSync(rec.uuid, rec.currency, rec.balance);
            }
        }
    }

    public void setBankBalancesBulk(java.util.Map<String, Double> bankBalances) {
        if (!isOnline()) {
            return;
        }
        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            redis.clients.jedis.Pipeline pipeline = jedis.pipelined();
            for (java.util.Map.Entry<String, Double> entry : bankBalances.entrySet()) {
                String bankName = entry.getKey();
                double balance = entry.getValue();
                pipeline.hset("vaultx:banks", bankName.toLowerCase(), String.valueOf(balance));
                String payload = serverId + ":BANK:" + bankName + ":" + balance;
                pipeline.publish(syncChannel, payload);
            }
            pipeline.sync();
        } catch (Exception e) {
            plugin.getLogger().warning("[VaultRedis] Failed to sync bulk bank updates: " + e.getMessage());
        }
    }

    public void saveUuidToRedis(final String name, final UUID uuid) {
        if (!isOnline())
            return;
        redisExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
                    jedis.hset("vaultx:uuid_cache", name.toLowerCase(), uuid.toString());
                    jedis.hset("vaultx:name_cache", uuid.toString(), name);
                } catch (Exception e) {
                    // ignore
                }
            }
        });
    }

    public UUID getUuidFromRedis(String name) {
        if (!isOnline())
            return null;
        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            String val = jedis.hget("vaultx:uuid_cache", name.toLowerCase());
            if (val != null) {
                return UUID.fromString(val);
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public String getNameFromRedis(UUID uuid) {
        if (!isOnline())
            return null;
        try (redis.clients.jedis.Jedis jedis = pool.getResource()) {
            return jedis.hget("vaultx:name_cache", uuid.toString());
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}

