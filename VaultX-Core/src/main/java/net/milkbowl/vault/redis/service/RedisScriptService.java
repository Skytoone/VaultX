package net.milkbowl.vault.redis.service;

import redis.clients.jedis.Jedis;

import java.util.Arrays;
import java.util.List;

/**
 * Service handling Lua script compilation, SHA-1 caching, and evaluation in Redis.
 */
public class RedisScriptService {

    public static final String UPDATE_BALANCE_STATS_LUA =
            "local oldScore = redis.call('zscore', KEYS[1], ARGV[1])\n" +
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
}
