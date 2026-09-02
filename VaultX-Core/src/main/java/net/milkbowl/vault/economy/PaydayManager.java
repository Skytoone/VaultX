package net.milkbowl.vault.economy;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import net.milkbowl.vault.permission.Permission;
import net.milkbowl.vault.redis.LocalFailoverManager;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class PaydayManager {

    private final Plugin plugin;
    private final LocalFailoverManager failoverManager;
    private final UUID treasuryUuid = UUID.nameUUIDFromBytes("GovernmentTreasury".getBytes(StandardCharsets.UTF_8));
    private long lastRunTime;
    private long intervalTicks;
    private org.bukkit.scheduler.BukkitTask paydayTask;

    public PaydayManager(Plugin plugin) {
        this.plugin = plugin;
        this.failoverManager = net.milkbowl.vault.Vault.getFailoverManager();
        this.lastRunTime = System.currentTimeMillis();
        long intervalMinutes = plugin.getConfig().getLong("paydays.interval-minutes", 60L);
        this.intervalTicks = intervalMinutes * 60L * 20L;
        startScheduler();
    }

    public void close() {
        if (paydayTask != null) {
            paydayTask.cancel();
            paydayTask = null;
        }
    }

    private void startScheduler() {
        paydayTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, new Runnable() {
            @Override
            public void run() {
                lastRunTime = System.currentTimeMillis();
                if (!plugin.getConfig().getBoolean("paydays.enabled", true)) {
                    return;
                }

                // 1. Gather online players and their primary groups on the main thread safely
                final List<PlayerData> playersToPay = new ArrayList<>();
                
                // We use a latch to run the check safely on Bukkit's main thread
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            RegisteredServiceProvider<Permission> permRsp = Bukkit.getServicesManager().getRegistration(Permission.class);
                            Permission perm = permRsp != null ? permRsp.getProvider() : null;

                            for (Player p : Bukkit.getOnlinePlayers()) {
                                String group = "default";
                                if (perm != null) {
                                    try {
                                        group = perm.getPrimaryGroup(p);
                                    } catch (Exception ignored) {}
                                }
                                if (group == null) {
                                    group = "default";
                                }
                                playersToPay.add(new PlayerData(p.getUniqueId(), p.getName(), group.toLowerCase()));
                            }
                        } finally {
                            latch.countDown();
                        }
                    }
                });

                try {
                    latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (playersToPay.isEmpty()) {
                    return;
                }

                // Pre-fetch player debts asynchronously since we are currently on the async thread
                if (failoverManager != null) {
                    for (PlayerData data : playersToPay) {
                        data.debt = failoverManager.getPlayerDebt(data.uuid);
                    }
                }

                // 2. Perform payments synchronously on the main thread
                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, new Runnable() {
                    @Override
                    public void run() {
                        RegisteredServiceProvider<Economy> econRsp = Bukkit.getServicesManager().getRegistration(Economy.class);
                        final Economy econ = econRsp != null ? econRsp.getProvider() : null;
                        if (econ == null) {
                            return;
                        }

                        boolean taxEnabled = plugin.getConfig().getBoolean("paydays.tax.enabled", true);
                        double taxPercent = net.milkbowl.vault.Vault.getDynamicTaxPercent("payday", plugin.getConfig().getDouble("paydays.tax.percent", 10.0));
                        String treasuryAccount = plugin.getConfig().getString("central-bank.taxes.treasury-account", "tresor_public");

                        for (final PlayerData data : playersToPay) {
                            double amount = plugin.getConfig().getDouble("paydays.groups." + data.group, -1.0);
                            if (amount < 0) {
                                amount = plugin.getConfig().getDouble("paydays.groups.default", 0.0);
                            }

                            if (amount <= 0.0) {
                                continue;
                            }

                            final double totalAmount = amount;
                            final double taxAmount = taxEnabled ? (totalAmount * (taxPercent / 100.0)) : 0.0;
                            final double netAmount = totalAmount - taxAmount;

                            Player p = Bukkit.getPlayer(data.uuid);
                            net.milkbowl.vault.util.FoliaScheduler.runEntitySync(plugin, p != null ? p : Bukkit.getOfflinePlayer(data.uuid).getPlayer(), () -> {
                                // Apply salary garnishment if debt exists
                                double debt = data.debt;
                                double garnished = 0.0;
                                double finalPaid = netAmount;

                                if (debt > 0.0) {
                                    garnished = Math.min(netAmount, debt);
                                    finalPaid = netAmount - garnished;
                                    final double newDebt = debt - garnished;
                                    final double finalGarnished = garnished;
                                    if (failoverManager != null) {
                                        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                                            failoverManager.updatePlayerDebt(data.uuid, newDebt);
                                            failoverManager.savePlayerTransaction(treasuryUuid, "DEPOSIT_GARNISHMENT", "default", finalGarnished, data.name);
                                            failoverManager.savePlayerTransaction(data.uuid, "WITHDRAW_GARNISHMENT", "default", finalGarnished, "Treasury");
                                        });
                                        econ.bankDeposit(treasuryAccount, garnished);
                                    }
                                }

                                // Deposit to player
                                econ.depositPlayer(Bukkit.getOfflinePlayer(data.uuid), finalPaid);

                                // Save transaction log for player
                                final double finalPaidForAsync = finalPaid;
                                if (failoverManager != null) {
                                    net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                                        failoverManager.savePlayerTransaction(data.uuid, "DEPOSIT_SALARY", "default", finalPaidForAsync, "Government");
                                    });
                                }

                                // Handle tax deposit to Treasury
                                if (taxEnabled && taxAmount > 0.0) {
                                    econ.bankDeposit(treasuryAccount, taxAmount);
                                    final double finalTaxAmount = taxAmount;
                                    if (failoverManager != null) {
                                        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                                            failoverManager.savePlayerTransaction(treasuryUuid, "DEPOSIT_TAX", "default", finalTaxAmount, data.name);
                                        });
                                    }
                                }

                                // Notify player if online
                                if (p != null && p.isOnline()) {
                                    if (garnished > 0.0) {
                                        p.sendMessage(net.milkbowl.vault.Vault.getMessage("payday.salary-garnished", "&c&l[Salary] &cSalary garnishment of &e%garnished% &cwas applied to repay your debt (Net paid: &e%net%&c).")
                                                .replace("%garnished%", econ.format(garnished))
                                                .replace("%net%", econ.format(finalPaid)));
                                    } else if (taxAmount > 0.0) {
                                        p.sendMessage(net.milkbowl.vault.Vault.getMessage("payday.salary-received-taxed", "&a&l[Salary] &aYou received your salary of &e%amount% &a(including &e%tax% &ataxes paid to Treasury).")
                                                .replace("%amount%", econ.format(finalPaid))
                                                .replace("%tax%", econ.format(taxAmount)));
                                    } else {
                                        p.sendMessage(net.milkbowl.vault.Vault.getMessage("payday.salary-received", "&a&l[Salary] &aYou received your salary of &e%amount%&a.")
                                                .replace("%amount%", econ.format(finalPaid)));
                                    }
                                }

                                if (net.milkbowl.vault.Vault.getDiscordManager() != null) {
                                    String webhookTemplate = net.milkbowl.vault.Vault.getMessage("discord.webhook-payday", "💰 **Payday Payout**\nUser **%player%** received salary of **%amount%** (Group: %group%)!");
                                    String content = webhookTemplate
                                            .replace("%player%", data.name)
                                            .replace("%amount%", econ.format(finalPaid))
                                            .replace("%group%", data.group);
                                    net.milkbowl.vault.Vault.getDiscordManager().sendWebhook("payday", content);
                                }
                            });
                        }
                    }
                });
            }
        }, intervalTicks, intervalTicks);
    }

    public long getNextPaydayTime() {
        long elapsed = System.currentTimeMillis() - lastRunTime;
        long intervalMs = (intervalTicks / 20) * 1000;
        long remaining = intervalMs - elapsed;
        return System.currentTimeMillis() + Math.max(0, remaining);
    }

    public PaydayBreakdown getPlayerBreakdown(Player player) {
        PaydayBreakdown breakdown = new PaydayBreakdown();
        
        // 1. Get group
        String group = "default";
        RegisteredServiceProvider<Permission> permRsp = Bukkit.getServicesManager().getRegistration(Permission.class);
        Permission perm = permRsp != null ? permRsp.getProvider() : null;
        if (perm != null) {
            try {
                group = perm.getPrimaryGroup(player);
            } catch (Exception ignored) {}
        }
        if (group == null) {
            group = "default";
        }
        breakdown.group = group;

        // 2. Get gross amount
        double amount = plugin.getConfig().getDouble("paydays.groups." + group.toLowerCase(), -1.0);
        if (amount < 0) {
            amount = plugin.getConfig().getDouble("paydays.groups.default", 0.0);
        }
        breakdown.grossAmount = amount;

        // 3. Tax info
        boolean taxEnabled = plugin.getConfig().getBoolean("paydays.tax.enabled", true);
        double taxPercent = net.milkbowl.vault.Vault.getDynamicTaxPercent("payday", plugin.getConfig().getDouble("paydays.tax.percent", 10.0));
        breakdown.taxPercent = taxPercent;
        breakdown.taxAmount = taxEnabled ? (amount * (taxPercent / 100.0)) : 0.0;
        
        double tempNet = amount - breakdown.taxAmount;

        // 4. Debt info
        double debt = failoverManager != null ? failoverManager.getPlayerDebt(player.getUniqueId()) : 0.0;
        breakdown.debt = debt;
        breakdown.garnishedAmount = debt > 0.0 ? Math.min(tempNet, debt) : 0.0;
        
        // 5. Net amount
        breakdown.netAmount = tempNet - breakdown.garnishedAmount;
        
        // 6. Next payday
        breakdown.nextPaydayTime = getNextPaydayTime();
        
        return breakdown;
    }

    public static class PaydayBreakdown {
        public String group;
        public double grossAmount;
        public double taxPercent;
        public double taxAmount;
        public double debt;
        public double garnishedAmount;
        public double netAmount;
        public long nextPaydayTime;
    }

    private static class PlayerData {
        final UUID uuid;
        final String name;
        final String group;
        double debt;

        PlayerData(UUID uuid, String name, String group) {
            this.uuid = uuid;
            this.name = name;
            this.group = group;
        }
    }
}

