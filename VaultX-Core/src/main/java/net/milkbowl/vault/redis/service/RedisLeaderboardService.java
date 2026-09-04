package net.milkbowl.vault.redis.service;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles Redis leaderboard calculation, stats updating via Lua scripts, and in-memory leaderboard caching.
 */
public class RedisLeaderboardService {

    public static class LeaderboardEntry {
        public final String name;
        public final double balance;

        public LeaderboardEntry(String name, double balance) {
            this.name = name;
            this.balance = balance;
        }
    }

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

    private final Plugin plugin;
    private final JedisPool pool;
    private final ScheduledExecutorService redisExecutor;
    private final Map<String, List<LeaderboardEntry>> leaderboardCaches = new ConcurrentHashMap<>();
    private ScheduledFuture<?> leaderboardTaskFuture;
    private String updateScriptSha1 = null;

    public RedisLeaderboardService(Plugin plugin, JedisPool pool, ScheduledExecutorService redisExecutor) {
        this.plugin = plugin;
        this.pool = pool;
        this.redisExecutor = redisExecutor;
    }

    public void updateLeaderboardAndStats(Jedis jedis, String curr, String member, double score) {
        String leaderboardKey = "vaultx:leaderboard:" + curr;
        String totalMoneyKey = "vaultx:stats:total_money:" + curr;
        String accountsCountKey = "vaultx:stats:accounts_count:" + curr;

        List<String> keys = Arrays.asList(leaderboardKey, totalMoneyKey, accountsCountKey);
        List<String> args = Arrays.asList(member, String.valueOf(score));

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

    public void startLeaderboardUpdater(boolean isOnline) {
        long interval = plugin.getConfig().getLong("redis.leaderboard-cache-interval-seconds", 60);
        leaderboardTaskFuture = redisExecutor.scheduleAtFixedRate(() -> {
            if (!isOnline || pool == null || pool.isClosed()) return;
            try (Jedis jedis = pool.getResource()) {
                java.util.Set<String> keys = jedis.keys("vaultx:leaderboard:*");
                for (String key : keys) {
                    String currency = key.substring("vaultx:leaderboard:".length());
                    java.util.List<redis.clients.jedis.resps.Tuple> range = jedis.zrevrangeWithScores(key, 0, 9);
                    List<LeaderboardEntry> list = new ArrayList<>();
                    for (redis.clients.jedis.resps.Tuple tuple : range) {
                        String uuidStr = tuple.getElement();
                        double score = tuple.getScore();
                        org.bukkit.OfflinePlayer p = Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuidStr));
                        String name = (p != null && p.getName() != null) ? p.getName() : uuidStr.substring(0, 8);
                        list.add(new LeaderboardEntry(name, score));
                    }
                    leaderboardCaches.put(currency, list);
                }
            } catch (Exception e) {
                // Suppress background sync errors
            }
        }, 5, interval, TimeUnit.SECONDS);
    }

    public void stopLeaderboardUpdater() {
        if (leaderboardTaskFuture != null) {
            try {
                leaderboardTaskFuture.cancel(false);
            } catch (Exception ignored) {}
        }
        leaderboardCaches.clear();
    }

    public List<LeaderboardEntry> getCachedLeaderboard(String currency) {
        return leaderboardCaches.getOrDefault(currency == null ? "default" : currency, new ArrayList<>());
    }
}
