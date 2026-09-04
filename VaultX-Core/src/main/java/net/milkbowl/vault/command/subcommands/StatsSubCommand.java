package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StatsSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public StatsSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "stats";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.admin";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("vault.admin")) {
            sender.sendMessage(parent.getMsg("commands.admin.no-permission", "§cYou do not have permission to execute admin commands."));
            return true;
        }

        Economy econ = parent.getEconomy();
        if (args.length >= 2 && args[1].equalsIgnoreCase("macro")) {
            handleMacroStatsText(sender, econ);
            return true;
        }

        sender.sendMessage(parent.getMsg("commands.admin.stats-fetching", "§eFetching telemetry statistics... Please wait."));

        long hits = 0;
        long misses = 0;
        double hitRate = 0.0;
        if (econ instanceof net.milkbowl.vault.economy.OptimizedEconomy optEcon) {
            hits = optEcon.getCacheHits();
            misses = optEcon.getCacheMisses();
            long total = hits + misses;
            hitRate = total == 0 ? 0.0 : ((double) hits / total) * 100.0;
        }

        double writeLatency = 0.0;
        net.milkbowl.vault.redis.LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm != null) {
            writeLatency = fm.getAverageBatchWriteLatencyMs();
        }

        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
        boolean redisOnline = redis != null && redis.isOnline();

        final long finalHits = hits;
        final long finalMisses = misses;
        final double finalHitRate = hitRate;
        final double finalWriteLatency = writeLatency;

        parent.runAsync(() -> {
            List<String> currencies = parent.getCurrencies(econ);
            Map<String, String> currencyStatsLines = new LinkedHashMap<>();

            for (String currency : currencies) {
                if (redisOnline) {
                    net.milkbowl.vault.redis.VaultRedisManager.CurrencyStats stats = redis.getGlobalCurrencyStats(currency);
                    if (stats != null) {
                        String totalStr = econ != null ? econ.format(stats.totalMoney) : String.format("%.2f", stats.totalMoney);
                        String avgStr = econ != null ? econ.format(stats.averageBalance) : String.format("%.2f", stats.averageBalance);
                        currencyStatsLines.put(currency,
                                parent.getMsg("commands.admin.stats-supply-details",
                                        "§7- Total Money: §a%total% §7| Accounts: §e%accounts% §7| Avg: §a%avg%")
                                        .replace("%total%", totalStr)
                                        .replace("%accounts%", String.valueOf(stats.accountsCount))
                                        .replace("%avg%", avgStr));
                    } else {
                        currencyStatsLines.put(currency, parent.getMsg("commands.admin.stats-supply-unavailable", "§7- Statistics not available."));
                    }
                } else {
                    if (fm != null) {
                        net.milkbowl.vault.redis.LocalFailoverManager.LocalCurrencyStats stats = fm.getLocalCurrencyStats(currency);
                        String totalStr = econ != null ? econ.format(stats.totalMoney) : String.format("%.2f", stats.totalMoney);
                        String avgStr = econ != null ? econ.format(stats.averageBalance) : String.format("%.2f", stats.averageBalance);
                        currencyStatsLines.put(currency,
                                parent.getMsg("commands.admin.stats-supply-details",
                                        "§7- Total Money: §a%total% §7| Accounts: §e%accounts% §7| Avg: §a%avg%")
                                        .replace("%total%", totalStr)
                                        .replace("%accounts%", String.valueOf(stats.accountsCount))
                                        .replace("%avg%", avgStr));
                    } else {
                        currencyStatsLines.put(currency, parent.getMsg("commands.admin.stats-supply-unavailable", "§7- Statistics not available."));
                    }
                }
            }

            parent.runSync(() -> {
                sender.sendMessage(parent.getMsg("commands.admin.stats-header", "§b§l=== VaultX Telemetry & Statistics ==="));
                if (econ instanceof net.milkbowl.vault.economy.OptimizedEconomy) {
                    sender.sendMessage(parent.getMsg("commands.admin.stats-cache-rate",
                            "§fCache Hit Rate: §e%rate%% §7(%hits% hits, %misses% misses)")
                            .replace("%rate%", String.format("%.2f", finalHitRate))
                            .replace("%hits%", String.valueOf(finalHits))
                            .replace("%misses%", String.valueOf(finalMisses)));
                } else {
                    sender.sendMessage(parent.getMsg("commands.admin.stats-cache-na", "§fCache Hit Rate: §eN/A §7(Standard Vault)"));
                }
                sender.sendMessage(parent.getMsg("commands.admin.stats-latency", "§fLocal DB Write Latency: §e%latency% ms")
                        .replace("%latency%", String.format("%.2f", finalWriteLatency)));
                sender.sendMessage(parent.getMsg("commands.admin.stats-redis", "§fRedis Connection: %status%")
                        .replace("%status%", redisOnline ? "§aONLINE" : "§cOFFLINE"));

                sender.sendMessage(parent.getMsg("commands.admin.stats-supply-header", "§b-- Currency Supply (%source%) --")
                        .replace("%source%", redisOnline ? "Global/Redis" : "Local DB"));
                for (Map.Entry<String, String> entry : currencyStatsLines.entrySet()) {
                    sender.sendMessage(parent.getMsg("commands.admin.stats-supply-entry", "  §f%currency% :")
                            .replace("%currency%", entry.getKey().toUpperCase()));
                    sender.sendMessage("  " + entry.getValue());
                }
                sender.sendMessage(parent.getMsg("commands.admin.stats-footer", "§b§l======================================"));
            });
        });
        return true;
    }

    private void handleMacroStatsText(CommandSender sender, Economy econ) {
        sender.sendMessage(parent.getMsg("commands.admin.stats-fetching", "§eFetching telemetry statistics... Please wait."));

        net.milkbowl.vault.redis.LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm == null) {
            sender.sendMessage(parent.getMsg("commands.transactions.database-error", "§cDatabase manager is not initialized."));
            return;
        }

        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
        boolean redisOnline = redis != null && redis.isOnline();

        parent.runAsync(() -> {
            List<String> currencies = parent.getCurrencies(econ);
            Map<String, net.milkbowl.vault.redis.LocalFailoverManager.LocalCurrencyStats> currencyStats = new HashMap<>();

            for (String currency : currencies) {
                if (redisOnline) {
                    net.milkbowl.vault.redis.VaultRedisManager.CurrencyStats stats = redis.getGlobalCurrencyStats(currency);
                    if (stats != null) {
                        currencyStats.put(currency, new net.milkbowl.vault.redis.LocalFailoverManager.LocalCurrencyStats(stats.totalMoney, stats.accountsCount, stats.averageBalance));
                    } else {
                        currencyStats.put(currency, fm.getLocalCurrencyStats(currency));
                    }
                } else {
                    currencyStats.put(currency, fm.getLocalCurrencyStats(currency));
                }
            }

            net.milkbowl.vault.economy.CentralBankManager cb = Vault.getCentralBankManager();
            final String treasuryAccount = cb != null ? cb.getTreasuryAccount() : "tresor_public";
            double cbBalanceVal = econ != null ? econ.bankBalance(treasuryAccount).balance : 0.0;
            if (cbBalanceVal == 0 && (econ == null || !econ.bankBalance(treasuryAccount).transactionSuccess())) {
                cbBalanceVal = fm.getBankBalance(treasuryAccount);
            }
            final double cbBalance = cbBalanceVal;

            final double bankLoansDebt = fm.getTotalBankLoansDebt();
            final double playerDebts = fm.getTotalPlayerDebts();
            final double totalDebt = bankLoansDebt + playerDebts;

            final List<net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry> leaderboard;
            if (redisOnline) {
                leaderboard = redis.getLeaderboard("default");
            } else {
                leaderboard = fm.getLocalLeaderboard("default", 10);
            }

            parent.runSync(() -> {
                sender.sendMessage(parent.getMsg("commands.admin.stats-header", "§b§l=== VaultX Macroeconomic Statistics ==="));

                sender.sendMessage("§b-- Currency Supply & Accounts --");
                for (String curr : currencies) {
                    net.milkbowl.vault.redis.LocalFailoverManager.LocalCurrencyStats stats = currencyStats.get(curr);
                    double total = stats != null ? stats.totalMoney : 0.0;
                    long accs = stats != null ? stats.accountsCount : 0;
                    double avg = stats != null ? stats.averageBalance : 0.0;

                    String formatTotal = curr.equals("default") && econ != null ? econ.format(total)
                            : String.format("%.2f %s", total, curr.toUpperCase());
                    String formatAvg = curr.equals("default") && econ != null ? econ.format(avg)
                            : String.format("%.2f %s", avg, curr.toUpperCase());

                    sender.sendMessage("  §f" + curr.toUpperCase() + ":");
                    sender.sendMessage("    §7- Total Money Supply: §a" + formatTotal);
                    sender.sendMessage("    §7- Total Accounts: §e" + accs);
                    sender.sendMessage("    §7- Average Balance: §b" + formatAvg);
                }

                sender.sendMessage("§b-- Central Bank Reserve --");
                sender.sendMessage("  §fReserve (" + treasuryAccount + "): §e" + (econ != null ? econ.format(cbBalance) : String.valueOf(cbBalance)));

                sender.sendMessage("§b-- Macroeconomic Debts --");
                sender.sendMessage("  §fActive Bank Loans: §e" + (econ != null ? econ.format(bankLoansDebt) : String.valueOf(bankLoansDebt)));
                sender.sendMessage("  §fPlayer Garnishments: §e" + (econ != null ? econ.format(playerDebts) : String.valueOf(playerDebts)));
                sender.sendMessage("  §c§lTotal Debts: §e" + (econ != null ? econ.format(totalDebt) : String.valueOf(totalDebt)));

                sender.sendMessage("§b-- Rich List (Top 10 Default Currency) --");
                int rank = 1;
                for (net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry entry : leaderboard) {
                    if (rank > 10) break;
                    sender.sendMessage(String.format("  §e#%d  §f%s  §8»  §a%s", rank, entry.name, econ != null ? econ.format(entry.balance) : String.valueOf(entry.balance)));
                    rank++;
                }
                if (leaderboard.isEmpty()) {
                    sender.sendMessage("  §cNo rich list data available.");
                }

                sender.sendMessage(parent.getMsg("commands.admin.stats-footer", "§b§l========================================="));
            });
        });
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
