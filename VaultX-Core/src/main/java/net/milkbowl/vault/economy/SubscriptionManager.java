package net.milkbowl.vault.economy;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.redis.LocalFailoverManager.SubscriptionRecord;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import net.milkbowl.vault.economy.events.VaultSubscriptionRenewEvent;
import java.util.List;
import java.util.UUID;

public class SubscriptionManager {

    private final Plugin plugin;
    private final LocalFailoverManager failoverManager;
    private BukkitTask mainTask;

    public SubscriptionManager(Plugin plugin) {
        this.plugin = plugin;
        this.failoverManager = Vault.getFailoverManager();
        startScheduler();
    }

    public void close() {
        if (mainTask != null) {
            mainTask.cancel();
            mainTask = null;
        }
    }

    private Economy getEconomy() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    private void startScheduler() {
        boolean enabled = plugin.getConfig().getBoolean("subscriptions.enabled", true);
        if (!enabled) {
            return;
        }
        long intervalMinutes = plugin.getConfig().getLong("subscriptions.check-interval-minutes", 1L);
        long ticks = Math.max(20L, intervalMinutes * 60L * 20L); // Minimum 1 second check to avoid performance drops
        mainTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, this::processSubscriptions, ticks, ticks);
    }

    private void processSubscriptions() {
        if (failoverManager == null)
            return;

        long now = System.currentTimeMillis();
        List<SubscriptionRecord> due = failoverManager.getDueSubscriptions(now);
        if (due.isEmpty())
            return;

        Economy econ = getEconomy();
        if (econ == null)
            return;

        // Pre-fetch bank account owners and balances asynchronously
        java.util.Map<String, UUID> bankOwners = new java.util.HashMap<>();
        java.util.Map<String, Double> dbBankBalances = new java.util.HashMap<>();
        for (SubscriptionRecord sub : due) {
            if (sub.targetType.equalsIgnoreCase("BANK")) {
                String bankName = sub.target.toLowerCase();
                bankOwners.put(bankName, failoverManager.getBankAccountOwner(bankName));
                dbBankBalances.put(bankName, failoverManager.getBankBalance(bankName));
            }
        }

        net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
            java.util.Set<String> updatedBanks = new java.util.HashSet<>();
            for (SubscriptionRecord sub : due) {
                processSingleSubscription(econ, sub, now, bankOwners, dbBankBalances, updatedBanks);
            }

            // Write all final updated bank balances asynchronously to database
            if (!updatedBanks.isEmpty()) {
                java.util.Map<String, Double> finalBankBalances = new java.util.HashMap<>();
                for (String bank : updatedBanks) {
                    finalBankBalances.put(bank, dbBankBalances.get(bank));
                }
                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                    for (java.util.Map.Entry<String, Double> entry : finalBankBalances.entrySet()) {
                        failoverManager.saveBankBalance(entry.getKey(), entry.getValue());
                    }
                });
            }
        });
    }

    public void processSingleSubscription(Economy econ, SubscriptionRecord sub, long now) {
        java.util.Map<String, UUID> bankOwners = new java.util.HashMap<>();
        java.util.Map<String, Double> dbBankBalances = new java.util.HashMap<>();
        if (sub.targetType.equalsIgnoreCase("BANK")) {
            String bankName = sub.target.toLowerCase();
            bankOwners.put(bankName, failoverManager.getBankAccountOwner(bankName));
            dbBankBalances.put(bankName, failoverManager.getBankBalance(bankName));
        }
        java.util.Set<String> updatedBanks = new java.util.HashSet<>();
        processSingleSubscription(econ, sub, now, bankOwners, dbBankBalances, updatedBanks);

        if (!updatedBanks.isEmpty()) {
            for (String bank : updatedBanks) {
                double newBal = dbBankBalances.get(bank);
                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                    failoverManager.saveBankBalance(bank, newBal);
                });
            }
        }
    }

    public void processSingleSubscription(Economy econ, SubscriptionRecord sub, long now, 
                                          java.util.Map<String, UUID> bankOwners, 
                                          java.util.Map<String, Double> dbBankBalances) {
        java.util.Set<String> updatedBanks = new java.util.HashSet<>();
        processSingleSubscription(econ, sub, now, bankOwners, dbBankBalances, updatedBanks);

        if (!updatedBanks.isEmpty()) {
            for (String bank : updatedBanks) {
                double newBal = dbBankBalances.get(bank);
                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                    failoverManager.saveBankBalance(bank, newBal);
                });
            }
        }
    }

    public void processSingleSubscription(Economy econ, SubscriptionRecord sub, long now, 
                                          java.util.Map<String, UUID> bankOwners, 
                                          java.util.Map<String, Double> dbBankBalances,
                                          java.util.Set<String> updatedBanks) {
        OfflinePlayer subscriber = Bukkit.getOfflinePlayer(sub.subscriber);
        double balance;
        String currency = sub.currency.toLowerCase();

        if (currency.equals("default")) {
            balance = econ.getBalance(subscriber);
        } else if (econ instanceof MultiCurrencyEconomy) {
            balance = ((MultiCurrencyEconomy) econ).getCurrencyBalance(subscriber, currency);
        } else {
            suspendSubscription(sub, "Unsupported currency (" + currency.toUpperCase() + ")");
            return;
        }

        if (balance < sub.amount) {
            suspendSubscription(sub, "Insufficient balance (" + formatAmount(econ, sub.amount, currency) + " required)");
            return;
        }

        // Withdraw from subscriber
        EconomyResponse wRes;
        if (currency.equals("default")) {
            wRes = econ.withdrawPlayer(subscriber, sub.amount);
        } else {
            wRes = ((MultiCurrencyEconomy) econ).withdrawCurrencyPlayer(subscriber, currency, sub.amount);
        }

        if (!wRes.transactionSuccess()) {
            suspendSubscription(sub, "Withdrawal failed: " + wRes.errorMessage);
            return;
        }

        boolean depositSuccess = false;
        String targetName = sub.target;

        if (sub.targetType.equalsIgnoreCase("BANK")) {
            String bankName = sub.target.toLowerCase();
            if (econ.getBanks().contains(bankName) || bankOwners.get(bankName) != null) {
                EconomyResponse dRes = econ.bankDeposit(bankName, sub.amount);
                if (dRes.transactionSuccess()) {
                    depositSuccess = true;
                }
            } else {
                refundSubscriber(econ, subscriber, sub.amount, currency);
                suspendSubscription(sub, "Target bank not found: " + sub.target);
                return;
            }
        } else {
            UUID targetUuid = UUID.fromString(sub.target);
            OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetUuid);
            targetName = targetPlayer.getName() != null ? targetPlayer.getName() : "Unknown";

            if (targetPlayer.isOnline() && targetPlayer.getPlayer() != null) {
                EconomyResponse dRes;
                if (currency.equals("default")) {
                    dRes = econ.depositPlayer(targetPlayer, sub.amount);
                } else {
                    dRes = ((MultiCurrencyEconomy) econ).depositCurrencyPlayer(targetPlayer, currency, sub.amount);
                }
                if (dRes.transactionSuccess()) {
                    depositSuccess = true;
                    Player onlineTarget = targetPlayer.getPlayer();
                    onlineTarget.sendMessage(Vault.getMessage("subscriptions.payment-received",
                            "§a§l[Subscription] §aYou received a subscription payment of §e%amount% §afrom §f%subscriber%§a.")
                            .replace("%amount%", formatAmount(econ, sub.amount, currency))
                            .replace("%subscriber%", subscriber.getName() != null ? subscriber.getName() : "Unknown"));
                }
            } else {
                Vault.getMailboxManager().sendOfflineMail(targetUuid,
                        subscriber.getName() != null ? subscriber.getName() : "Unknown",
                        "Periodic subscription payment",
                        sub.amount,
                        currency);
                depositSuccess = true;
            }
        }

        if (depositSuccess) {
            Bukkit.getPluginManager().callEvent(new VaultSubscriptionRenewEvent(subscriber, String.valueOf(sub.id), currency, sub.amount, true));
            final String finalTargetName = targetName;
            final double finalAmount = sub.amount;
            final String finalCurrency = currency;
            net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                failoverManager.savePlayerTransaction(sub.subscriber, "WITHDRAW_SUB_BILL", finalCurrency, finalAmount, finalTargetName);
                if (sub.targetType.equalsIgnoreCase("PLAYER")) {
                    failoverManager.savePlayerTransaction(UUID.fromString(sub.target), "DEPOSIT_SUB_BILL", finalCurrency, finalAmount,
                            subscriber.getName() != null ? subscriber.getName() : "Unknown");
                }
            });

            long next = now + ((long) sub.intervalHours * 3600000L);
            net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                failoverManager.updateSubscriptionBilling(sub.id, now, next, "ACTIVE");
            });

            if (subscriber.isOnline() && subscriber.getPlayer() != null) {
                subscriber.getPlayer().sendMessage(Vault.getMessage("subscriptions.billing-success",
                        "§a§l[Subscription] §aAutomatic billing of §e%amount% §afor your subscription to §f%target%§a.")
                        .replace("%amount%", formatAmount(econ, sub.amount, currency))
                        .replace("%target%", targetName));
            }
        } else {
            refundSubscriber(econ, subscriber, sub.amount, currency);
            suspendSubscription(sub, "Failed to deposit to recipient.");
        }
    }

    private void refundSubscriber(Economy econ, OfflinePlayer subscriber, double amount, String currency) {
        if (currency.equals("default")) {
            econ.depositPlayer(subscriber, amount);
        } else if (econ instanceof MultiCurrencyEconomy) {
            ((MultiCurrencyEconomy) econ).depositCurrencyPlayer(subscriber, currency, amount);
        }
    }

    private void suspendSubscription(SubscriptionRecord sub, String reason) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            failoverManager.updateSubscriptionBilling(sub.id, sub.lastBilling, sub.nextBilling, "SUSPENDED");
        });

        OfflinePlayer subscriber = Bukkit.getOfflinePlayer(sub.subscriber);
        String targetName = sub.target;
        if (sub.targetType.equalsIgnoreCase("PLAYER")) {
            OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(UUID.fromString(sub.target));
            targetName = targetPlayer.getName() != null ? targetPlayer.getName() : "Unknown";
        }

        String alertMsg = "Subscription to " + targetName + " suspended. Reason: " + reason;
        Vault.getMailboxManager().sendOfflineMail(sub.subscriber, "Subscription System", alertMsg, 0.0, "default");

        if (subscriber.isOnline() && subscriber.getPlayer() != null) {
            subscriber.getPlayer().sendMessage(Vault.getMessage("subscriptions.billing-suspended",
                    "§c§l[Subscription] §cYour subscription to §e%target% §chas been suspended! Reason: §f%reason%")
                    .replace("%target%", targetName)
                    .replace("%reason%", reason));
        }
    }

    private String formatAmount(Economy econ, double amount, String currency) {
        if (currency.equals("default")) {
            return econ.format(amount);
        }
        return econ.format(amount) + " (" + currency.toUpperCase() + ")";
    }
}

