package net.milkbowl.vault.redis;

import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

/**
 * Encapsulates Redis pool lifecycle, configuration, and connectivity verification.
 */
public class RedisConnectionFactory {

    private final Plugin plugin;
    private final JedisPool pool;

    public RedisConnectionFactory(Plugin plugin, String host, int port, String password) {
        this.plugin = plugin;
        int maxConnections = plugin.getConfig().getInt("redis.max-connections", 16);
        int timeoutMs = plugin.getConfig().getInt("redis.connection-timeout-ms", 250);

        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(maxConnections);
        config.setMaxIdle(maxConnections);
        config.setMinIdle(Math.max(2, maxConnections / 4));
        config.setBlockWhenExhausted(true);
        config.setMaxWait(Duration.ofMillis(timeoutMs));

        if (password != null && !password.isEmpty()) {
            this.pool = new JedisPool(config, host, port, timeoutMs, password);
        } else {
            this.pool = new JedisPool(config, host, port, timeoutMs);
        }
    }

    public JedisPool getPool() {
        return pool;
    }

    public boolean checkConnection() {
        if (pool == null || pool.isClosed()) return false;
        try (Jedis jedis = pool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    public void close() {
        if (pool != null && !pool.isClosed()) {
            try {
                pool.close();
            } catch (Exception ignored) {
            }
        }
    }
}
