package net.milkbowl.vault.redis;

import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.UUID;

/**
 * Service encapsulating Redis Pub/Sub subscription, AES payload encryption/decryption, and channel message publishing.
 */
public class RedisPubSubService {

    private final Plugin plugin;
    private final JedisPool pool;
    private final String syncChannel;
    private final RedisPayloadEncryptor encryptor;

    public RedisPubSubService(Plugin plugin, JedisPool pool, String syncChannel, String encryptionKey) {
        this.plugin = plugin;
        this.pool = pool;
        this.syncChannel = syncChannel;
        this.encryptor = new RedisPayloadEncryptor(encryptionKey);
    }

    public RedisPayloadEncryptor getEncryptor() {
        return encryptor;
    }

    public void publishPayload(Jedis jedis, String payload) {
        if (jedis == null || payload == null) return;
        jedis.publish(syncChannel, encryptor.encrypt(payload));
    }

    public void publishPayload(String payload) {
        if (pool == null || pool.isClosed() || payload == null) return;
        try (Jedis jedis = pool.getResource()) {
            publishPayload(jedis, payload);
        } catch (Exception e) {
            plugin.getLogger().warning("[VaultRedis] Failed to publish payload: " + e.getMessage());
        }
    }

    public void publishBalanceUpdate(String serverId, UUID uuid, String currency, double balance) {
        long timestamp = System.currentTimeMillis();
        String curr = currency == null ? "default" : currency.toLowerCase();
        String payload = serverId + ":" + uuid.toString() + ":" + curr + ":" + balance + ":" + timestamp;
        publishPayload(payload);
    }

    public void publishBankUpdate(String serverId, String bankName, double balance) {
        String payload = serverId + ":BANK:" + bankName + ":" + balance;
        publishPayload(payload);
    }
}
