package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReportSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public ReportSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "report";
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

        int daysVal = 7;
        if (args.length >= 3) {
            try {
                daysVal = Integer.parseInt(args[2]);
                if (daysVal < 1) daysVal = 7;
            } catch (NumberFormatException e) {
                sender.sendMessage(parent.getMsg("commands.admin.report-invalid-days", "§cInvalid number of days. Using default: 7 days."));
            }
        }

        final int days = daysVal;
        net.milkbowl.vault.redis.LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm == null) {
            sender.sendMessage(parent.getMsg("commands.transactions.database-error", "§cDatabase manager is not initialized."));
            return true;
        }

        sender.sendMessage(parent.getMsg("commands.admin.report-fetching", "§eGenerating inflation analytics report... Please wait."));

        parent.runAsync(() -> {
            List<net.milkbowl.vault.redis.LocalFailoverManager.AnalyticsReportEntry> report = fm.getAnalyticsReport(days);

            parent.runSync(() -> {
                if (report.isEmpty()) {
                    sender.sendMessage(parent.getMsg("commands.admin.report-empty",
                            "§d§l[VaultX Report] §cNo transaction records found for the last %days% days.")
                            .replace("%days%", String.valueOf(days)));
                    return;
                }

                sender.sendMessage(parent.getMsg("commands.admin.report-header",
                        "§d§l=== VaultX Inflation & Ledger Report (Last %days% Days) ===")
                        .replace("%days%", String.valueOf(days)));
                Economy econ = parent.getEconomy();
                Map<String, List<net.milkbowl.vault.redis.LocalFailoverManager.AnalyticsReportEntry>> grouped = new HashMap<>();
                for (net.milkbowl.vault.redis.LocalFailoverManager.AnalyticsReportEntry entry : report) {
                    grouped.computeIfAbsent(entry.currency.toUpperCase(), k -> new ArrayList<>()).add(entry);
                }

                for (Map.Entry<String, List<net.milkbowl.vault.redis.LocalFailoverManager.AnalyticsReportEntry>> currencyEntry : grouped.entrySet()) {
                    sender.sendMessage(parent.getMsg("commands.admin.report-currency-header", "§bCurrency: §l%currency%")
                            .replace("%currency%", currencyEntry.getKey()));
                    double totalCreated = 0;
                    double totalDestroyed = 0;
                    for (net.milkbowl.vault.redis.LocalFailoverManager.AnalyticsReportEntry entry : currencyEntry.getValue()) {
                        totalCreated += entry.created;
                        totalDestroyed += entry.destroyed;
                        String createdStr = econ != null ? econ.format(entry.created) : String.format("%.2f", entry.created);
                        String destroyedStr = econ != null ? econ.format(entry.destroyed) : String.format("%.2f", entry.destroyed);
                        sender.sendMessage(parent.getMsg("commands.admin.report-category-entry",
                                "  §f%category% §7- Created: §a%created% §7| Destroyed: §c%destroyed%")
                                .replace("%category%", entry.category)
                                .replace("%created%", createdStr)
                                .replace("%destroyed%", destroyedStr));
                    }
                    double netInflation = totalCreated - totalDestroyed;
                    String netColor = netInflation >= 0 ? "§a+" : "§c";
                    String netStr = econ != null ? econ.format(netInflation) : String.format("%.2f", netInflation);
                    sender.sendMessage(parent.getMsg("commands.admin.report-net-inflation", "  §7Net Inflation: %color%%amount%")
                            .replace("%color%", netColor)
                            .replace("%amount%", netStr));
                }
                sender.sendMessage(parent.getMsg("commands.admin.report-footer",
                        "§d§l==========================================================="));
            });
        });
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
