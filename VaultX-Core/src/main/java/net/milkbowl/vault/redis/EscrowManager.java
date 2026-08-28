package net.milkbowl.vault.redis;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

public class EscrowManager {

    private final Plugin plugin;
    private final LocalFailoverManager failoverManager;
    private org.bukkit.scheduler.BukkitTask autoRefundTask;
    private static final java.util.Set<String> PROCESSING_ESCROWS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public EscrowManager(Plugin plugin) {
        this.plugin = plugin;
        this.failoverManager = Vault.getFailoverManager();
        startAutoRefundScheduler();
    }

    public void close() {
        if (autoRefundTask != null) {
            autoRefundTask.cancel();
            autoRefundTask = null;
        }
    }

    private VaultRedisManager getRedisManager() {
        return VaultRedisManager.getInstance();
    }

    private Economy getEconomy() {
        org.bukkit.plugin.RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    public static class EscrowDetails {
        public final String id;
        public final UUID sender;
        public final UUID receiver;
        public final double amount;
        public final String currency;
        public final String status;
        public final long timeoutAt;

        public EscrowDetails(String id, UUID sender, UUID receiver, double amount, String currency, String status, long timeoutAt) {
            this.id = id;
            this.sender = sender;
            this.receiver = receiver;
            this.amount = amount;
            this.currency = currency;
            this.status = status;
            this.timeoutAt = timeoutAt;
        }
    }

    public static class EscrowResult {
        public final boolean success;
        public final String message;
        public final String escrowId;

        public EscrowResult(boolean success, String message, String escrowId) {
            this.success = success;
            this.message = message;
            this.escrowId = escrowId;
        }
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

        // Fallback to SQLite/MySQL
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

    public java.util.concurrent.CompletableFuture<EscrowResult> startEscrow(Player sender, OfflinePlayer receiver, double amount, String currency, long timeoutSec) {
        java.util.concurrent.CompletableFuture<EscrowResult> future = new java.util.concurrent.CompletableFuture<>();
        if (!plugin.getConfig().getBoolean("escrow.enabled", true)) {
            future.complete(new EscrowResult(false, Vault.getMessage("escrow.disabled", "§cEscrow is disabled on this server."), null));
            return future;
        }

        int maxActive = plugin.getConfig().getInt("escrow.max-active-per-player", 5);
        if (maxActive > 0) {
            List<EscrowDetails> currentEscrows = listEscrows(sender).join();
            long activeCount = currentEscrows.stream().filter(e -> e != null && e.status.equalsIgnoreCase("PENDING")).count();
            if (activeCount >= maxActive) {
                future.complete(new EscrowResult(false, Vault.getMessage("escrow.limit-exceeded", "§cYou cannot have more than %limit% active escrow transactions.").replace("%limit%", String.valueOf(maxActive)), null));
                return future;
            }
        }

        long actualTimeoutSec = timeoutSec > 0 ? timeoutSec : plugin.getConfig().getLong("escrow.default-timeout-seconds", 300L);

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            VaultRedisManager redis = getRedisManager();
            String lockKey = sender.getUniqueId().toString() + ":" + currency.toLowerCase();
            String lockVal = UUID.randomUUID().toString();
            boolean locked = false;
            if (redis != null && redis.isOnline()) {
                locked = redis.acquireLock(lockKey, lockVal, 5000);
                if (!locked) {
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.lock-active", "&cAccount lock active. Please try again."), null));
                    return;
                }
            }

            try {
                java.util.concurrent.CompletableFuture<Boolean> withdrawFuture = new java.util.concurrent.CompletableFuture<>();
                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                    Economy econ = getEconomy();
                    if (econ == null) {
                        withdrawFuture.complete(false);
                        return;
                    }
                    double bal = 0;
                    if (currency.equalsIgnoreCase("default")) {
                        bal = econ.getBalance(sender);
                    } else if (econ instanceof MultiCurrencyEconomy) {
                        bal = ((MultiCurrencyEconomy) econ).getCurrencyBalance(sender, currency);
                    }
                    if (bal < amount) {
                        withdrawFuture.complete(false);
                        return;
                    }

                    EconomyResponse response;
                    if (currency.equalsIgnoreCase("default")) {
                        response = econ.withdrawPlayer(sender, amount);
                    } else if (econ instanceof MultiCurrencyEconomy) {
                        response = ((MultiCurrencyEconomy) econ).withdrawCurrencyPlayer(sender, currency, amount);
                    } else {
                        response = new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Multi-currency not supported");
                    }
                    withdrawFuture.complete(response.transactionSuccess());
                });

                if (!withdrawFuture.join()) {
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.insufficient-funds", "&cYou do not have enough funds or the transaction failed."), null));
                    return;
                }

                String escrowId = UUID.randomUUID().toString().substring(0, 8); // Shorter ID for commands
                long timeoutAt = System.currentTimeMillis() + (actualTimeoutSec * 1000L);
                
                failoverManager.saveLocalEscrow(escrowId, sender.getUniqueId(), receiver.getUniqueId(), amount, currency, "PENDING", timeoutAt);

                if (redis != null && redis.isOnline()) {
                    try (Jedis jedis = redis.getPool().getResource()) {
                        String key = "vaultx:escrows:" + escrowId;
                        Map<String, String> data = new HashMap<>();
                        data.put("sender", sender.getUniqueId().toString());
                        data.put("receiver", receiver.getUniqueId().toString());
                        data.put("amount", String.valueOf(amount));
                        data.put("currency", currency.toLowerCase());
                        data.put("status", "PENDING");
                        data.put("timeout_at", String.valueOf(timeoutAt));
                        jedis.hset(key, data);
                        jedis.sadd("vaultx:player_escrows:" + sender.getUniqueId().toString(), escrowId);
                        jedis.sadd("vaultx:player_escrows:" + receiver.getUniqueId().toString(), escrowId);
                        jedis.zadd("vaultx:escrows_timeout", timeoutAt, escrowId);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[VaultX Escrow] Failed to save escrow to Redis: " + e.getMessage());
                    }
                }

                future.complete(new EscrowResult(true, Vault.getMessage("escrow.started", "&aEscrow transaction successfully started."), escrowId));
            } catch (Exception e) {
                future.complete(new EscrowResult(false, Vault.getMessage("general.unknown-error", "&cAn error occurred: %error%").replace("%error%", e.getMessage()), null));
            } finally {
                if (redis != null && locked) {
                    redis.releaseLock(lockKey, lockVal);
                }
            }
        });
        return future;
    }

    public java.util.concurrent.CompletableFuture<EscrowResult> releaseEscrow(String escrowId, org.bukkit.command.CommandSender confirmer) {
        java.util.concurrent.CompletableFuture<EscrowResult> future = new java.util.concurrent.CompletableFuture<>();
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            if (!PROCESSING_ESCROWS.add(escrowId)) {
                future.complete(new EscrowResult(false, "Cette transaction est déjà en cours de traitement.", null));
                return;
            }
            VaultRedisManager redis = getRedisManager();
            String lockKey = "escrow:" + escrowId;
            String lockVal = UUID.randomUUID().toString();
            boolean locked = false;
            if (redis != null && redis.isOnline()) {
                locked = redis.acquireLock(lockKey, lockVal, 5000);
                if (!locked) {
                    PROCESSING_ESCROWS.remove(escrowId);
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.lock-active", "&cAccount lock active. Please try again."), null));
                    return;
                }
            }

            try {
                EscrowDetails escrow = getEscrow(escrowId);
                if (escrow == null) {
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.not-found", "&cEscrow transaction not found."), null));
                    return;
                }

                if (!escrow.status.equalsIgnoreCase("PENDING")) {
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.not-pending", "&cTransaction is not pending (current status: %status%).").replace("%status%", escrow.status), null));
                    return;
                }

                boolean isSender = confirmer instanceof Player && ((Player) confirmer).getUniqueId().equals(escrow.sender);
                boolean isAdmin = confirmer.hasPermission("vault.admin");
                if (!isSender && !isAdmin) {
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.release-no-permission", "&cOnly the sender or an administrator can confirm the release of funds."), null));
                    return;
                }

                failoverManager.saveLocalEscrow(escrowId, escrow.sender, escrow.receiver, escrow.amount, escrow.currency, "COMPLETED", escrow.timeoutAt);
                if (redis != null && redis.isOnline()) {
                    try (Jedis jedis = redis.getPool().getResource()) {
                        jedis.hset("vaultx:escrows:" + escrowId, "status", "COMPLETED");
                        jedis.zrem("vaultx:escrows_timeout", escrowId);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[VaultX Escrow] Failed to update status to COMPLETED in Redis: " + e.getMessage());
                    }
                }

                java.util.concurrent.CompletableFuture<Boolean> depositFuture = new java.util.concurrent.CompletableFuture<>();
                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                    Economy econ = getEconomy();
                    if (econ == null) {
                        depositFuture.complete(false);
                        return;
                    }
                    OfflinePlayer receiver = Bukkit.getOfflinePlayer(escrow.receiver);
                    EconomyResponse response;
                    if (escrow.currency.equalsIgnoreCase("default")) {
                        response = econ.depositPlayer(receiver, escrow.amount);
                    } else if (econ instanceof MultiCurrencyEconomy) {
                        response = ((MultiCurrencyEconomy) econ).depositCurrencyPlayer(receiver, escrow.currency, escrow.amount);
                    } else {
                        response = new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Multi-currency not supported");
                    }
                    depositFuture.complete(response.transactionSuccess());
                });

                if (depositFuture.join()) {
                    future.complete(new EscrowResult(true, Vault.getMessage("escrow.released-success", "&aEscrow funds successfully released to the receiver."), escrowId));
                } else {
                    failoverManager.saveLocalEscrow(escrowId, escrow.sender, escrow.receiver, escrow.amount, escrow.currency, "PENDING", escrow.timeoutAt);
                    if (redis != null && redis.isOnline()) {
                        try (Jedis jedis = redis.getPool().getResource()) {
                            jedis.hset("vaultx:escrows:" + escrowId, "status", "PENDING");
                            jedis.zadd("vaultx:escrows_timeout", escrow.timeoutAt, escrowId);
                        } catch (Exception ignored) {}
                    }
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.release-deposit-failed", "&cFailed to deposit funds to the receiver."), null));
                }
            } catch (Exception e) {
                future.complete(new EscrowResult(false, Vault.getMessage("general.unknown-error", "&cAn error occurred: %error%").replace("%error%", e.getMessage()), null));
            } finally {
                PROCESSING_ESCROWS.remove(escrowId);
                if (redis != null && locked) {
                    redis.releaseLock(lockKey, lockVal);
                }
            }
        });
        return future;
    }

    public java.util.concurrent.CompletableFuture<EscrowResult> refundEscrow(String escrowId, org.bukkit.command.CommandSender requestor) {
        java.util.concurrent.CompletableFuture<EscrowResult> future = new java.util.concurrent.CompletableFuture<>();
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            if (!PROCESSING_ESCROWS.add(escrowId)) {
                future.complete(new EscrowResult(false, "Cette transaction est déjà en cours de traitement.", null));
                return;
            }
            VaultRedisManager redis = getRedisManager();
            String lockKey = "escrow:" + escrowId;
            String lockVal = UUID.randomUUID().toString();
            boolean locked = false;
            if (redis != null && redis.isOnline()) {
                locked = redis.acquireLock(lockKey, lockVal, 5000);
                if (!locked) {
                    PROCESSING_ESCROWS.remove(escrowId);
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.lock-active", "&cAccount lock active. Please try again."), null));
                    return;
                }
            }

            try {
                EscrowDetails escrow = getEscrow(escrowId);
                if (escrow == null) {
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.not-found", "&cEscrow transaction not found."), null));
                    return;
                }

                if (!escrow.status.equalsIgnoreCase("PENDING")) {
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.not-pending", "&cTransaction is not pending (current status: %status%).").replace("%status%", escrow.status), null));
                    return;
                }

                boolean isSender = requestor instanceof Player && ((Player) requestor).getUniqueId().equals(escrow.sender);
                boolean isReceiver = requestor instanceof Player && ((Player) requestor).getUniqueId().equals(escrow.receiver);
                boolean isAdmin = requestor.hasPermission("vault.admin");
                boolean isSystem = requestor == Bukkit.getConsoleSender();

                boolean allowed = isSystem || isReceiver || isAdmin;
                if (isSender && !allowed) {
                    if (System.currentTimeMillis() >= escrow.timeoutAt) {
                        allowed = true;
                    } else {
                        long remainingSec = (escrow.timeoutAt - System.currentTimeMillis()) / 1000L;
                        future.complete(new EscrowResult(false, Vault.getMessage("escrow.refund-wait", "&cYou cannot cancel this escrow yet. Please wait %time% seconds.").replace("%time%", String.valueOf(remainingSec)), null));
                        return;
                    }
                }

                if (!allowed) {
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.refund-no-permission", "&cYou do not have permission to cancel this escrow."), null));
                    return;
                }

                failoverManager.saveLocalEscrow(escrowId, escrow.sender, escrow.receiver, escrow.amount, escrow.currency, "REFUNDED", escrow.timeoutAt);
                if (redis != null && redis.isOnline()) {
                    try (Jedis jedis = redis.getPool().getResource()) {
                        jedis.hset("vaultx:escrows:" + escrowId, "status", "REFUNDED");
                        jedis.zrem("vaultx:escrows_timeout", escrowId);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[VaultX Escrow] Failed to update status to REFUNDED in Redis: " + e.getMessage());
                    }
                }

                java.util.concurrent.CompletableFuture<Boolean> depositFuture = new java.util.concurrent.CompletableFuture<>();
                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                    Economy econ = getEconomy();
                    if (econ == null) {
                        depositFuture.complete(false);
                        return;
                    }
                    OfflinePlayer senderPlayer = Bukkit.getOfflinePlayer(escrow.sender);
                    EconomyResponse response;
                    if (escrow.currency.equalsIgnoreCase("default")) {
                        response = econ.depositPlayer(senderPlayer, escrow.amount);
                    } else if (econ instanceof MultiCurrencyEconomy) {
                        response = ((MultiCurrencyEconomy) econ).depositCurrencyPlayer(senderPlayer, escrow.currency, escrow.amount);
                    } else {
                        response = new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Multi-currency not supported");
                    }
                    depositFuture.complete(response.transactionSuccess());
                });

                if (depositFuture.join()) {
                    future.complete(new EscrowResult(true, Vault.getMessage("escrow.refunded-success", "&aEscrow funds successfully refunded to the sender."), escrowId));
                } else {
                    failoverManager.saveLocalEscrow(escrowId, escrow.sender, escrow.receiver, escrow.amount, escrow.currency, "PENDING", escrow.timeoutAt);
                    if (redis != null && redis.isOnline()) {
                        try (Jedis jedis = redis.getPool().getResource()) {
                            jedis.hset("vaultx:escrows:" + escrowId, "status", "PENDING");
                            jedis.zadd("vaultx:escrows_timeout", escrow.timeoutAt, escrowId);
                        } catch (Exception ignored) {}
                    }
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.refund-deposit-failed", "&cFailed to refund funds to the sender."), null));
                }
            } catch (Exception e) {
                future.complete(new EscrowResult(false, Vault.getMessage("general.unknown-error", "&cAn error occurred: %error%").replace("%error%", e.getMessage()), null));
            } finally {
                PROCESSING_ESCROWS.remove(escrowId);
                if (redis != null && locked) {
                    redis.releaseLock(lockKey, lockVal);
                }
            }
        });
        return future;
    }

    private void startAutoRefundScheduler() {
        autoRefundTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, () -> {
            long now = System.currentTimeMillis();
            VaultRedisManager redis = getRedisManager();
            if (redis != null && redis.isOnline()) {
                try (Jedis jedis = redis.getPool().getResource()) {
                    java.util.List<String> expiredIds = jedis.zrangeByScore("vaultx:escrows_timeout", 0, now);
                    if (expiredIds != null && !expiredIds.isEmpty()) {
                        for (String id : expiredIds) {
                            refundEscrow(id, Bukkit.getConsoleSender()).thenAccept(result -> {
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
                    refundEscrow(record.id, Bukkit.getConsoleSender()).thenAccept(result -> {
                        if (result.success) {
                            plugin.getLogger().info("[VaultX Escrow] Auto-refunded expired local escrow " + record.id);
                        }
                    });
                }
            }
        }, 600L, 600L);
    }

    public java.util.concurrent.CompletableFuture<List<EscrowDetails>> listEscrows(OfflinePlayer player) {
        java.util.concurrent.CompletableFuture<List<EscrowDetails>> future = new java.util.concurrent.CompletableFuture<>();
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            List<EscrowDetails> list = new ArrayList<>();
            VaultRedisManager redis = getRedisManager();
            if (redis != null && redis.isOnline()) {
                try (Jedis jedis = redis.getPool().getResource()) {
                    java.util.Set<String> ids = jedis.smembers("vaultx:player_escrows:" + player.getUniqueId().toString());
                    if (ids != null) {
                        for (String id : ids) {
                            EscrowDetails details = getEscrow(id);
                            if (details != null) {
                                list.add(details);
                            }
                        }
                    }
                    future.complete(list);
                    return;
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
            future.complete(list);
        });
        return future;
    }
}

