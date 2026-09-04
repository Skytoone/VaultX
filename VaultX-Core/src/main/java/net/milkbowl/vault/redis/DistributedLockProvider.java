package net.milkbowl.vault.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;

/**
 * Service providing distributed lock management using Redis SET NX EX and LUA scripts for safe lock release.
 */
public class DistributedLockProvider {

    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then\n" +
            "    return redis.call('del', KEYS[1])\n" +
            "else\n" +
            "    return 0\n" +
            "end";

    private final JedisPool pool;

    public DistributedLockProvider(JedisPool pool) {
        this.pool = pool;
    }

    public boolean acquireLock(String lockKey, String lockValue, long leaseTimeMs) {
        if (pool == null || pool.isClosed()) return false;
        try (Jedis jedis = pool.getResource()) {
            SetParams params = SetParams.setParams().nx().px(leaseTimeMs);
            String result = jedis.set("vaultx:lock:" + lockKey, lockValue, params);
            return "OK".equalsIgnoreCase(result);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean releaseLock(String lockKey, String lockValue) {
        if (pool == null || pool.isClosed()) return false;
        try (Jedis jedis = pool.getResource()) {
            Object result = jedis.eval(UNLOCK_LUA, Collections.singletonList("vaultx:lock:" + lockKey), Collections.singletonList(lockValue));
            return Long.valueOf(1).equals(result);
        } catch (Exception e) {
            return false;
        }
    }
}
