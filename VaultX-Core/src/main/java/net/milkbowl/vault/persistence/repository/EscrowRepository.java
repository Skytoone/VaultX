package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.economy.VaultEscrowAPI.EscrowDetails;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.redis.VaultRedisManager;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;

import java.util.*;

public class EscrowRepository {

    private final Plugin plugin;
    private final LocalFailoverManager failoverManager;

    public EscrowRepository(Plugin plugin, LocalFailoverManager failoverManager) {
        this.plugin = plugin;
        this.failoverManager = failoverManager;
    }

    private VaultRedisManager getRedisManager() {
        return VaultRedisManager.getInstance();
    }

    public EscrowDetails getEscrow(String escrowId) {
        VaultRedisManager redis = getRedisManager();
        if (redis != null && redis.isOnline()) {
            try (Jedis jedis = redis.getPool().getResource()) {
                String key = "vaultx:escrows:" + escrowId;
                if (jedis.exists(key)) {
                    Map<String, String> data = jedis.hgetAll(key);
                    if (data != null && !data.isEmpty()) {
                        return new EscrowDetails(
                                escrowId,
                                UUID.fromString(data.get("sender")),
                                UUID.fromString(data.get("receiver")),
                                Double.parseDouble(data.get("amount")),
                                data.get("currency"),
                                data.get("status"),
                                Long.parseLong(data.get("timeout_at"))
                        );
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[VaultX Escrow] Failed to fetch escrow " + escrowId + " from Redis: " + e.getMessage());
            }
        }

        LocalFailoverManager.LocalEscrowRecord localRecord = failoverManager.getLocalEscrow(escrowId);
        if (localRecord != null) {
            return new EscrowDetails(
                    localRecord.id,
                    UUID.fromString(localRecord.sender),
                    UUID.fromString(localRecord.receiver),
                    localRecord.amount,
                    localRecord.currency,
                    localRecord.status,
                    localRecord.timeoutAt
            );
        }
        return null;
    }

    public void saveEscrow(String escrowId, UUID sender, UUID receiver, double amount, String currency, String status, long timeoutAt) {
        failoverManager.saveLocalEscrow(escrowId, sender, receiver, amount, currency, status, timeoutAt);
        VaultRedisManager redis = getRedisManager();
        if (redis != null && redis.isOnline()) {
            try (Jedis jedis = redis.getPool().getResource()) {
                String key = "vaultx:escrows:" + escrowId;
                Map<String, String> data = new HashMap<>();
                data.put("sender", sender.toString());
                data.put("receiver", receiver.toString());
                data.put("amount", String.valueOf(amount));
                data.put("currency", currency.toLowerCase());
                data.put("status", status);
                data.put("timeout_at", String.valueOf(timeoutAt));
                jedis.hset(key, data);
                jedis.sadd("vaultx:player_escrows:" + sender.toString(), escrowId);
                jedis.sadd("vaultx:player_escrows:" + receiver.toString(), escrowId);
                if ("PENDING".equals(status)) {
                    jedis.zadd("vaultx:escrows_timeout", timeoutAt, escrowId);
                } else {
                    jedis.zrem("vaultx:escrows_timeout", escrowId);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[VaultX Escrow] Failed to save escrow to Redis: " + e.getMessage());
            }
        }
    }

    public List<EscrowDetails> listEscrows(OfflinePlayer player) {
        List<EscrowDetails> list = new ArrayList<>();
        VaultRedisManager redis = getRedisManager();
        if (redis != null && redis.isOnline()) {
            try (Jedis jedis = redis.getPool().getResource()) {
                Set<String> ids = jedis.smembers("vaultx:player_escrows:" + player.getUniqueId().toString());
                if (ids != null) {
                    for (String id : ids) {
                        EscrowDetails details = getEscrow(id);
                        if (details != null) {
                            list.add(details);
                        }
                    }
                }
                return list;
            } catch (Exception e) {
                plugin.getLogger().warning("[VaultX Escrow] Failed to list escrows from Redis: " + e.getMessage());
            }
        }

        List<LocalFailoverManager.LocalEscrowRecord> localList = failoverManager.getLocalEscrowsForPlayer(player.getUniqueId());
        for (LocalFailoverManager.LocalEscrowRecord record : localList) {
            list.add(new EscrowDetails(
                    record.id,
                    UUID.fromString(record.sender),
                    UUID.fromString(record.receiver),
                    record.amount,
                    record.currency,
                    record.status,
                    record.timeoutAt
            ));
        }
        return list;
    }
}
