package net.milkbowl.vault.redis.service;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.OptimizedEconomy;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.util.FoliaScheduler;

public class RedisMessageSyncHandler {

    private final Plugin plugin;
    private final String serverId;
    private final LocalFailoverManager failoverManager;

    public RedisMessageSyncHandler(Plugin plugin, String serverId, LocalFailoverManager failoverManager) {
        this.plugin = plugin;
        this.serverId = serverId;
        this.failoverManager = failoverManager;
    }

    public void handleSyncMessage(String message) {
        if (message == null || message.isEmpty()) return;
        String[] parts = message.split(":");
        if (parts.length < 4) return;

        String originServer = parts[0];
        if (originServer.equals(this.serverId)) {
            return; // Ignore own messages
        }

        String messageType = parts[1];

        switch (messageType) {
            case "BANK_MEMBER_UPDATE":
                processBankMemberUpdate(parts);
                break;
            case "BANK":
                processBankBalance(parts);
                break;
            case "FREEZE":
                processFreeze(parts);
                break;
            case "UNFREEZE":
                processUnfreeze(parts);
                break;
            default:
                processPlayerBalanceSync(parts);
                break;
        }
    }

    private void processBankMemberUpdate(String[] parts) {
        try {
            String bankName = parts[2];
            UUID uuid = UUID.fromString(parts[3]);
            String role = parts[4];
            if ("REMOVE".equals(role)) {
                failoverManager.removeBankMember(bankName, uuid);
            } else {
                failoverManager.addBankMember(bankName, uuid, role);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("[VaultRedis] Error processing BANK_MEMBER_UPDATE sync: " + e.getMessage());
        }
    }

    private void processBankBalance(String[] parts) {
        try {
            String bankName = parts[2];
            double balance = Double.parseDouble(parts[3]);
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null && rsp.getProvider() instanceof OptimizedEconomy optEcon) {
                optEcon.updateBankCacheFromRedis(bankName, balance);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("[VaultRedis] Error processing BANK sync: " + e.getMessage());
        }
    }

    private void processFreeze(String[] parts) {
        try {
            final UUID uuid = UUID.fromString(parts[2]);
            final String reason = parts.length > 3 ? parts[3] : "Synchronized from Redis";
            FoliaScheduler.runSync(plugin, () -> {
                if (net.milkbowl.vault.Vault.getFirewall() != null) {
                    net.milkbowl.vault.Vault.getFirewall().freezePlayerLocal(uuid, reason);
                }
            });
        } catch (Exception e) {
            plugin.getLogger().fine("[VaultRedis] Error processing FREEZE sync: " + e.getMessage());
        }
    }

    private void processUnfreeze(String[] parts) {
        try {
            final UUID uuid = UUID.fromString(parts[2]);
            FoliaScheduler.runSync(plugin, () -> {
                if (net.milkbowl.vault.Vault.getFirewall() != null) {
                    net.milkbowl.vault.Vault.getFirewall().unfreezePlayerLocal(uuid);
                }
            });
        } catch (Exception e) {
            plugin.getLogger().fine("[VaultRedis] Error processing UNFREEZE sync: " + e.getMessage());
        }
    }

    private void processPlayerBalanceSync(String[] parts) {
        try {
            UUID uuid = UUID.fromString(parts[1]);
            String currency = parts[2];
            double balance = Double.parseDouble(parts[3]);
            long timestamp = parts.length > 4 ? Long.parseLong(parts[4]) : System.currentTimeMillis();

            long localTimestamp = failoverManager.getCustomCurrencyTimestamp(uuid, currency);
            if (timestamp >= localTimestamp) {
                boolean isDefault = currency.equalsIgnoreCase("default");
                failoverManager.saveCustomCurrencyBalance(uuid, isDefault ? "default" : currency, balance, timestamp);
                
                RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
                if (rsp != null && rsp.getProvider() instanceof OptimizedEconomy optEcon) {
                    optEcon.updateCacheFromRedis(uuid, currency, balance);
                }
                if (isDefault) {
                    failoverManager.updateDelegateBalance(uuid, balance);
                }
            }
        } catch (Exception ignored) {
        }
    }
}
