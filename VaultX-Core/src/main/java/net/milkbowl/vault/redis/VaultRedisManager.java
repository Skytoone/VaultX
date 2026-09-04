package net.milkbowl.vault.redis;

import org.bukkit.plugin.Plugin;
import redis.clients.jedis.JedisPool;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import net.milkbowl.vault.redis.service.RedisLeaderboardService;
import net.milkbowl.vault.redis.service.RedisSyncQueueService;

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

    private final RedisLeaderboardService leaderboardService;
    private final RedisSyncQueueService syncQueueService;
    private final net.milkbowl.vault.redis.service.RedisMessageSyncHandler syncHandler;

    private final net.milkbowl.vault.redis.service.RedisScriptService scriptService = new net.milkbowl.vault.redis.service.RedisScriptService();

    private void updateLeaderboardAndStats(redis.clients.jedis.Jedis jedis, String curr, String member, double score) {
        scriptService.updateLeaderboardAndStats(jedis, curr, member, score);
    }


    private final RedisPayloadEncryptor encryptor;
    private final RedisConnectionFactory connectionFactory;
    private final DistributedLockProvider lockProvider;
    private final RedisPubSubService pubSubService;
    private final RedisConnectionManager connectionManager;

    public VaultRedisManager(Plugin plugin, String host, int port, String password, String serverId, String channel) {
        this.plugin = plugin;
        this.serverId = serverId;
        this.syncChannel = channel;
        this.failoverManager = net.milkbowl.vault.Vault.getFailoverManager();
        this.syncHandler = new net.milkbowl.vault.redis.service.RedisMessageSyncHandler(plugin, serverId, failoverManager);

        this.connectionFactory = new RedisConnectionFactory(plugin, host, port, password);
        this.pool = connectionFactory.getPool();

        String encryptionKey = plugin.getConfig().getString("redis.encryption-key", "");
        this.pubSubService = new RedisPubSubService(plugin, this.pool, channel, encryptionKey);
        this.encryptor = pubSubService.getEncryptor();
        this.lockProvider = new DistributedLockProvider(this.pool);

        this.connectionManager = new RedisConnectionManager(plugin, pool, channel, encryptor);
        this.redisExecutor = connectionManager.getExecutor();

        this.leaderboardService = new RedisLeaderboardService(plugin, pool, redisExecutor);
        this.syncQueueService = new RedisSyncQueueService(pool, serverId, channel, encryptor, failoverManager, redisExecutor, leaderboardService);

        instance = this;
        this.connectionManager.setCallbacks(
            () -> {
                failoverManager.processQueue(VaultRedisManager.this);
                syncLocalBanksToRedis();
                syncFrozenPlayers();
            },
            null,
            this::handleSyncMessage
        );
        this.connectionManager.start();
        startLeaderboardUpdater();

        if (this.isOnline()) {
            syncFrozenPlayers();
        }

        plugin.getLogger().info("[VaultRedis] Redis synchronization enabled for server: " + serverId + (encryptor.isEnabled() ? " (AES Encrypted)" : ""));
    }

    public RedisConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    public DistributedLockProvider getLockProvider() {
        return lockProvider;
    }

    public RedisPubSubService getPubSubService() {
        return pubSubService;
    }

    public RedisPayloadEncryptor getEncryptor() {
        return encryptor;
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

    public void close() {
        stopLeaderboardUpdater();
        if (syncQueueService != null) {
            syncQueueService.flushAndClear(isOnline());
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
        if (pool != null) {
            try {
                pool.close();
            } catch (Exception ignored) {}
        }
        instance = null;
    }

    private void publishPayload(redis.clients.jedis.Jedis jedis, String payload) {
        if (connectionManager != null) {
            connectionManager.publishPayload(payload);
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
        if (syncHandler != null) {
            syncHandler.handleSyncMessage(message);
        }
    }

    public void publishBalanceUpdate(final UUID playerUuid, final String currency, final double newBalance) {
        syncQueueService.queueBalancePublish(playerUuid, currency, newBalance, isOnline());
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
                    publishPayload(jedis, payload);
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
                    publishPayload(jedis, payload);
                } catch (Exception e) {
                    plugin.getLogger().warning("[VaultRedis] Failed to sync bank update: " + e.getMessage());
                }
            }
        });
    }

    public boolean isOnline() {
        return connectionManager != null && connectionManager.isOnline();
    }

    public boolean checkConnection() {
        return connectionManager != null && connectionManager.checkConnection();
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
                    publishPayload(jedis, payload);
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
                    publishPayload(jedis, payload);
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
                    publishPayload(jedis, payload);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to sync custom currency update: " + e.getMessage());
                }
            }
        });
    }

    private void startLeaderboardUpdater() {
        if (leaderboardService != null) {
            leaderboardService.startLeaderboardUpdater(isOnline());
        }
    }

    public void stopLeaderboardUpdater() {
        if (leaderboardService != null) {
            leaderboardService.stopLeaderboardUpdater();
        }
    }

    public List<LeaderboardEntry> getLeaderboard(String currency) {
        if (leaderboardService != null) {
            List<RedisLeaderboardService.LeaderboardEntry> entries = leaderboardService.getCachedLeaderboard(currency);
            List<LeaderboardEntry> result = new ArrayList<>();
            for (RedisLeaderboardService.LeaderboardEntry e : entries) {
                result.add(new LeaderboardEntry(e.name, e.balance));
            }
            return result;
        }
        return java.util.Collections.emptyList();
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
            publishPayload(jedis, payload);
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
                pipeline.eval(net.milkbowl.vault.redis.service.RedisScriptService.UPDATE_BALANCE_STATS_LUA,
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

