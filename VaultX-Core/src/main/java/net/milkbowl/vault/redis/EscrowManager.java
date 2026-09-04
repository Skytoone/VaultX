package net.milkbowl.vault.redis;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.VaultEscrowAPI;
import net.milkbowl.vault.economy.events.VaultEscrowCreateEvent;
import net.milkbowl.vault.persistence.repository.EscrowRepository;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

public class EscrowManager implements VaultEscrowAPI {

    private final Plugin plugin;
    private final EscrowRepository escrowRepository;
    private final EscrowExpirationEngine expirationEngine;
    private final java.util.Set<String> processingEscrows = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public EscrowManager(Plugin plugin) {
        this.plugin = plugin;
        LocalFailoverManager failoverManager = Vault.getFailoverManager();
        this.escrowRepository = new EscrowRepository(plugin, failoverManager);
        this.expirationEngine = new EscrowExpirationEngine(plugin, this, failoverManager);
        this.expirationEngine.start();
    }

    public void close() {
        if (expirationEngine != null) {
            expirationEngine.close();
        }
        processingEscrows.clear();
    }

    private VaultRedisManager getRedisManager() {
        return VaultRedisManager.getInstance();
    }

    private Economy getEconomy() {
        if (!Vault.getWrappedEconomies().isEmpty()) {
            return Vault.getWrappedEconomies().get(0);
        }
        org.bukkit.plugin.RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    public static class EscrowDetails extends VaultEscrowAPI.EscrowDetails {
        public EscrowDetails(String id, UUID sender, UUID receiver, double amount, String currency, String status, long timeoutAt) {
            super(id, sender, receiver, amount, currency, status, timeoutAt);
        }
    }

    public static class EscrowResult extends VaultEscrowAPI.EscrowResult {
        public EscrowResult(boolean success, String message, String escrowId) {
            super(success, message, escrowId);
        }
    }

    public EscrowDetails getEscrow(String escrowId) {
        VaultEscrowAPI.EscrowDetails d = escrowRepository.getEscrow(escrowId);
        if (d == null) return null;
        if (d instanceof EscrowDetails) return (EscrowDetails) d;
        return new EscrowDetails(d.id, d.sender, d.receiver, d.amount, d.currency, d.status, d.timeoutAt);
    }

    public java.util.concurrent.CompletableFuture<VaultEscrowAPI.EscrowResult> startEscrow(Player sender, OfflinePlayer receiver, double amount, String currency, long timeoutSec) {
        java.util.concurrent.CompletableFuture<VaultEscrowAPI.EscrowResult> future = new java.util.concurrent.CompletableFuture<>();
        if (!plugin.getConfig().getBoolean("escrow.enabled", true)) {
            future.complete(new EscrowResult(false, Vault.getMessage("escrow.disabled", "§cEscrow is disabled on this server."), null));
            return future;
        }
        if (sender == null || receiver == null || Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            future.complete(new EscrowResult(false, Vault.getMessage("escrow.invalid-parameters", "§cInvalid escrow parameters."), null));
            return future;
        }

        VaultEscrowCreateEvent createEvent = new VaultEscrowCreateEvent(sender, receiver, amount, currency, timeoutSec);
        Bukkit.getPluginManager().callEvent(createEvent);
        if (createEvent.isCancelled()) {
            future.complete(new EscrowResult(false, "Escrow creation was cancelled by a plugin event.", null));
            return future;
        }
        final double finalAmountParam = createEvent.getAmount();
        final long finalTimeoutParam = createEvent.getTimeoutSec();

        final String safeCurrency = (currency == null || currency.trim().isEmpty()) ? "default" : currency.trim();
        long actualTimeoutSec = finalTimeoutParam > 0 ? finalTimeoutParam : plugin.getConfig().getLong("escrow.default-timeout-seconds", 300L);

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            int maxActive = plugin.getConfig().getInt("escrow.max-active-per-player", 5);
            if (maxActive > 0) {
                List<EscrowDetails> currentEscrows = listEscrows(sender).join();
                long activeCount = currentEscrows.stream().filter(e -> e != null && e.status.equalsIgnoreCase("PENDING")).count();
                if (activeCount >= maxActive) {
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.limit-exceeded", "§cYou cannot have more than %limit% active escrow transactions.").replace("%limit%", String.valueOf(maxActive)), null));
                    return;
                }
            }

            VaultRedisManager redis = getRedisManager();
            String lockKey = sender.getUniqueId().toString() + ":" + safeCurrency.toLowerCase();
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
                    if (safeCurrency.equalsIgnoreCase("default")) {
                        bal = econ.getBalance(sender);
                    } else if (econ instanceof MultiCurrencyEconomy) {
                        bal = ((MultiCurrencyEconomy) econ).getCurrencyBalance(sender, safeCurrency);
                    }
                    if (bal < finalAmountParam) {
                        withdrawFuture.complete(false);
                        return;
                    }

                    EconomyResponse response;
                    if (safeCurrency.equalsIgnoreCase("default")) {
                        response = econ.withdrawPlayer(sender, finalAmountParam);
                    } else if (econ instanceof MultiCurrencyEconomy) {
                        response = ((MultiCurrencyEconomy) econ).withdrawCurrencyPlayer(sender, safeCurrency, finalAmountParam);
                    } else {
                        response = new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Multi-currency not supported");
                    }
                    withdrawFuture.complete(response.transactionSuccess());
                });

                if (!withdrawFuture.join()) {
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.insufficient-funds", "&cYou do not have enough funds or the transaction failed."), null));
                    return;
                }

                String escrowId = UUID.randomUUID().toString().substring(0, 8);
                long timeoutAt = System.currentTimeMillis() + (actualTimeoutSec * 1000L);
                
                escrowRepository.saveEscrow(escrowId, sender.getUniqueId(), receiver.getUniqueId(), finalAmountParam, safeCurrency, "PENDING", timeoutAt);

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

    public java.util.concurrent.CompletableFuture<VaultEscrowAPI.EscrowResult> releaseEscrow(String escrowId, org.bukkit.command.CommandSender confirmer) {
        java.util.concurrent.CompletableFuture<VaultEscrowAPI.EscrowResult> future = new java.util.concurrent.CompletableFuture<>();
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            if (!processingEscrows.add(escrowId)) {
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
                    processingEscrows.remove(escrowId);
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.lock-active", "&cAccount lock active. Please try again."), null));
                    return;
                }
            }

            try {
                VaultEscrowAPI.EscrowDetails escrow = getEscrow(escrowId);
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

                escrowRepository.saveEscrow(escrowId, escrow.sender, escrow.receiver, escrow.amount, escrow.currency, "COMPLETED", escrow.timeoutAt);

                java.util.concurrent.CompletableFuture<Boolean> depositFuture = new java.util.concurrent.CompletableFuture<>();
                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                    Economy econ = getEconomy();
                    if (econ == null) {
                        depositFuture.complete(false);
                        return;
                    }
                    OfflinePlayer receiver = Bukkit.getOfflinePlayer(escrow.receiver);
                    double feePercent = plugin.getConfig().getDouble("escrow.service-fee-percent", 0.5);
                    double fee = (feePercent > 0) ? (escrow.amount * (feePercent / 100.0)) : 0.0;
                    double payout = Math.max(0.0, escrow.amount - fee);

                    EconomyResponse response;
                    if (escrow.currency.equalsIgnoreCase("default")) {
                        response = econ.depositPlayer(receiver, payout);
                    } else if (econ instanceof MultiCurrencyEconomy) {
                        response = ((MultiCurrencyEconomy) econ).depositCurrencyPlayer(receiver, escrow.currency, payout);
                    } else {
                        response = new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Multi-currency not supported");
                    }

                    if (response.transactionSuccess() && fee > 0 && Vault.getCentralBankManager() != null) {
                        Vault.getCentralBankManager().depositTreasury(escrow.currency, fee);
                    }
                    depositFuture.complete(response.transactionSuccess());
                });

                if (depositFuture.join()) {
                    future.complete(new EscrowResult(true, Vault.getMessage("escrow.released-success", "&aEscrow funds successfully released to the receiver."), escrowId));
                } else {
                    escrowRepository.saveEscrow(escrowId, escrow.sender, escrow.receiver, escrow.amount, escrow.currency, "PENDING", escrow.timeoutAt);
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.release-deposit-failed", "&cFailed to deposit funds to the receiver."), null));
                }
            } catch (Exception e) {
                future.complete(new EscrowResult(false, Vault.getMessage("general.unknown-error", "&cAn error occurred: %error%").replace("%error%", e.getMessage()), null));
            } finally {
                processingEscrows.remove(escrowId);
                if (redis != null && locked) {
                    redis.releaseLock(lockKey, lockVal);
                }
            }
        });
        return future;
    }

    public java.util.concurrent.CompletableFuture<VaultEscrowAPI.EscrowResult> refundEscrow(String escrowId, org.bukkit.command.CommandSender requestor) {
        java.util.concurrent.CompletableFuture<VaultEscrowAPI.EscrowResult> future = new java.util.concurrent.CompletableFuture<>();
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            if (!processingEscrows.add(escrowId)) {
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
                    processingEscrows.remove(escrowId);
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.lock-active", "&cAccount lock active. Please try again."), null));
                    return;
                }
            }

            try {
                VaultEscrowAPI.EscrowDetails escrow = getEscrow(escrowId);
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

                escrowRepository.saveEscrow(escrowId, escrow.sender, escrow.receiver, escrow.amount, escrow.currency, "REFUNDED", escrow.timeoutAt);

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
                    escrowRepository.saveEscrow(escrowId, escrow.sender, escrow.receiver, escrow.amount, escrow.currency, "PENDING", escrow.timeoutAt);
                    future.complete(new EscrowResult(false, Vault.getMessage("escrow.refund-deposit-failed", "&cFailed to refund funds to the sender."), null));
                }
            } catch (Exception e) {
                future.complete(new EscrowResult(false, Vault.getMessage("general.unknown-error", "&cAn error occurred: %error%").replace("%error%", e.getMessage()), null));
            } finally {
                processingEscrows.remove(escrowId);
                if (redis != null && locked) {
                    redis.releaseLock(lockKey, lockVal);
                }
            }
        });
        return future;
    }

    public java.util.concurrent.CompletableFuture<List<EscrowDetails>> listEscrows(OfflinePlayer player) {
        java.util.concurrent.CompletableFuture<List<EscrowDetails>> future = new java.util.concurrent.CompletableFuture<>();
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            List<VaultEscrowAPI.EscrowDetails> raw = escrowRepository.listEscrows(player);
            List<EscrowDetails> list = new ArrayList<>();
            if (raw != null) {
                for (VaultEscrowAPI.EscrowDetails d : raw) {
                    list.add(new EscrowDetails(d.id, d.sender, d.receiver, d.amount, d.currency, d.status, d.timeoutAt));
                }
            }
            future.complete(list);
        });
        return future;
    }

    @Override
    public java.util.concurrent.CompletableFuture<List<VaultEscrowAPI.EscrowDetails>> getPlayerEscrowsAsync(OfflinePlayer player) {
        return listEscrows(player).thenApply(list -> new ArrayList<>(list));
    }
}
