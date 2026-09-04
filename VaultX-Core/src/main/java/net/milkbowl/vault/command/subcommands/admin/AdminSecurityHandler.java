package net.milkbowl.vault.command.subcommands.admin;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.VaultXCommand;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.List;

public class AdminSecurityHandler {

    private final VaultXCommand parent;

    public AdminSecurityHandler(VaultXCommand parent) {
        this.parent = parent;
    }

    public boolean handleTax(CommandSender sender, String[] args) {
        if (args.length < 5 || !args[2].equalsIgnoreCase("set")) {
            sender.sendMessage(parent.getMsg("commands.admin.tax.usage",
                    "&cUsage: /vaultx admin tax set <payday/pay/exchange> <percent>"));
            return true;
        }
        String taxType = args[3].toLowerCase();
        if (!taxType.equals("payday") && !taxType.equals("pay") && !taxType.equals("exchange")) {
            sender.sendMessage(parent.getMsg("commands.admin.tax.invalid-type",
                    "&cInvalid tax type. Choices: payday, pay, exchange"));
            return true;
        }
        double percent;
        try {
            percent = parent.parseNonNegativeDouble(args[4]);
            if (percent > 100) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(parent.getMsg("commands.admin.tax.invalid-percent",
                    "&cInvalid percentage. It must be a number between 0 and 100."));
            return true;
        }
        Vault.setDynamicTaxPercent(taxType, percent);
        sender.sendMessage(parent.getMsg("commands.admin.tax.success", "&a&l✔ &aTax for &e%type% &ahas been set to &e%percent%%&a.")
                .replace("%type%", taxType)
                .replace("%percent%", String.valueOf(percent)));
        return true;
    }

    public void handleAudit(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(parent.getMsg("commands.admin.audit-usage", "§cUsage: /vaultx admin audit [player/all] [page]"));
            return;
        }

        String targetStr = args[2];
        int pageVal = 1;
        if (args.length >= 4) {
            try {
                pageVal = Integer.parseInt(args[3]);
                if (pageVal < 1) pageVal = 1;
            } catch (NumberFormatException e) {
                sender.sendMessage(parent.getMsg("commands.admin.audit-invalid-page", "§cInvalid page number. Using page 1."));
            }
        }

        final int page = pageVal;
        net.milkbowl.vault.redis.LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm == null) {
            sender.sendMessage(parent.getMsg("general.database-error", "§cLe gestionnaire de base de données n'est pas initialisé."));
            return;
        }

        sender.sendMessage(parent.getMsg("commands.admin.audit-fetching", "§eFetching security audits... Please wait."));

        parent.runAsync(() -> {
            int pageSize = 6;
            List<net.milkbowl.vault.redis.LocalFailoverManager.AuditRecord> records;

            if (targetStr.equalsIgnoreCase("all")) {
                records = fm.getSecurityAuditsAll(page, pageSize);
            } else {
                OfflinePlayer target = parent.resolvePlayerFast(targetStr);
                if (target == null) {
                    parent.runSync(() -> sender.sendMessage(parent.getMsg("general.player-not-found", "§cPlayer not found.")));
                    return;
                }
                records = fm.getSecurityAudits(target.getUniqueId(), page, pageSize);
            }

            parent.runSync(() -> {
                if (records.isEmpty()) {
                    sender.sendMessage(parent.getMsg("commands.admin.audit-empty",
                            "§e§l[VaultX Audit] §cNo audit logs found for '%target%' on page %page%.")
                            .replace("%target%", targetStr)
                            .replace("%page%", String.valueOf(page)));
                    return;
                }

                sender.sendMessage(parent.getMsg("commands.admin.audit-header", "§b§l=== VaultX Security Audits (Page %page%) ===")
                        .replace("%page%", String.valueOf(page)));
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (net.milkbowl.vault.redis.LocalFailoverManager.AuditRecord r : records) {
                    String time = sdf.format(new java.util.Date(r.timestamp));
                    String actionColor = r.action.startsWith("BLOCKED") || r.action.equals("FREEZE")
                            || r.action.contains("SPIKE") ? "§c" : "§a";
                    String amountStr = r.amount > 0 ? " | Amount: §e"
                            + (parent.getEconomy() != null ? parent.getEconomy().format(r.amount) : String.format("%.2f", r.amount))
                            : "";
                    sender.sendMessage(parent.getMsg("commands.admin.audit-entry",
                            "§7[%time%] %color%%action% §f%player%%amount% §7- %details%")
                            .replace("%time%", time)
                            .replace("%color%", actionColor)
                            .replace("%action%", r.action)
                            .replace("%player%", r.name)
                            .replace("%amount%", amountStr)
                            .replace("%details%", r.details));
                }
                sender.sendMessage(parent.getMsg("commands.admin.audit-footer", "§b§l======================================="));
            });
        });
    }
}
