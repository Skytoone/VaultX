package net.milkbowl.vault.redis;

import net.milkbowl.vault.economy.VaultEscrowAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;

import java.util.List;

public class EscrowExpirationEngine {

    private final Plugin plugin;
    private final VaultEscrowAPI escrowAPI;
    private final LocalFailoverManager failoverManager;
    private org.bukkit.scheduler.BukkitTask autoRefundTask;

    public EscrowExpirationEngine(Plugin plugin, VaultEscrowAPI escrowAPI, LocalFailoverManager failoverManager) {
        this.plugin = plugin;
        this.escrowAPI = escrowAPI;
        this.failoverManager = failoverManager;
    }

    public void start() {
        autoRefundTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, () -> {
            long now = System.currentTimeMillis();
            VaultRedisManager redis = VaultRedisManager.getInstance();
            if (redis != null && redis.isOnline()) {
                try (Jedis jedis = redis.getPool().getResource()) {
                    List<String> expiredIds = jedis.zrangeByScore("vaultx:escrows_timeout", 0, now);
                    if (expiredIds != null && !expiredIds.isEmpty()) {
                        for (String id : expiredIds) {
                            escrowAPI.refundEscrow(id, Bukkit.getConsoleSender()).thenAccept(result -> {
                                if (result.success) {
                                    plugin.getLogger().info("[VaultX Escrow] Auto-refunded expired escrow " + id);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[VaultX Escrow] Error in Redis auto-refund scheduler: " + e.getMessage());
                }
            } else {
                List<LocalFailoverManager.LocalEscrowRecord> expired = failoverManager.getExpiredPendingLocalEscrows(now);
                for (LocalFailoverManager.LocalEscrowRecord record : expired) {
                    escrowAPI.refundEscrow(record.id, Bukkit.getConsoleSender()).thenAccept(result -> {
                        if (result.success) {
                            plugin.getLogger().info("[VaultX Escrow] Auto-refunded expired local escrow " + record.id);
                        }
                    });
                }
            }
        }, 600L, 600L);
    }

    public void close() {
        if (autoRefundTask != null) {
            autoRefundTask.cancel();
            autoRefundTask = null;
        }
    }
}
