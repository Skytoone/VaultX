package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class LogsSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public LogsSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "logs";
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

        if (args.length < 2) {
            sender.sendMessage(parent.getMsg("commands.logs.usage", "§cUsage: /vaultx logs <player> [page]"));
            return true;
        }

        String targetName = args[1];
        int pageVal = 1;
        if (args.length >= 3) {
            try {
                pageVal = Integer.parseInt(args[2]);
                if (pageVal < 1) pageVal = 1;
            } catch (NumberFormatException e) {
                sender.sendMessage(parent.getMsg("commands.transactions.invalid-page", "§cInvalid page number."));
                return true;
            }
        }

        final int page = pageVal;
        net.milkbowl.vault.redis.LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm == null) {
            sender.sendMessage(parent.getMsg("commands.transactions.database-error", "§cDatabase manager is not initialized."));
            return true;
        }

        sender.sendMessage(parent.getMsg("commands.logs.fetching", "§eFetching transaction logs for %player%...")
                .replace("%player%", targetName));

        parent.runAsync(() -> {
            OfflinePlayer target = parent.resolvePlayerFast(targetName);
            if (target == null || (!net.milkbowl.vault.util.UUIDCache.hasPlayedBeforeFast(target) && !target.isOnline())) {
                parent.runSync(() -> {
                    sender.sendMessage(parent.getMsg("commands.admin.player-not-found", "§cPlayer '%player%' not found.")
                            .replace("%player%", targetName));
                });
                return;
            }

            int pageSize = 10;
            List<net.milkbowl.vault.redis.LocalFailoverManager.PlayerTransactionRecord> txs = fm
                    .getPlayerTransactions(target.getUniqueId(), page, pageSize);

            parent.runSync(() -> {
                if (txs.isEmpty()) {
                    sender.sendMessage(parent.getMsg("commands.logs.empty",
                            "§d§l[VaultX Logs] §cNo transaction logs found for %player% on page %page%.")
                            .replace("%player%", target.getName() != null ? target.getName() : targetName)
                            .replace("%page%", String.valueOf(page)));
                    return;
                }

                String resolvedName = target.getName() != null ? target.getName() : targetName;
                sender.sendMessage(parent.getMsg("commands.logs.header", "§b§l=== Transaction Logs: %player% (Page %page%) ===")
                        .replace("%player%", resolvedName)
                        .replace("%page%", String.valueOf(page)));

                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM HH:mm");
                java.text.SimpleDateFormat sdfHover = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                for (net.milkbowl.vault.redis.LocalFailoverManager.PlayerTransactionRecord tx : txs) {
                    String time = sdf.format(new java.util.Date(tx.timestamp));
                    String hoverTime = sdfHover.format(new java.util.Date(tx.timestamp));

                    String typeUpper = tx.type.toUpperCase();
                    boolean isDeposit = typeUpper.startsWith("DEPOSIT") || typeUpper.startsWith("CLAIMED")
                            || typeUpper.contains("DIVIDEND") || typeUpper.contains("SALARY");
                    String actionColor = isDeposit ? "§a" : "§c";
                    String actionSymbol = isDeposit ? "+" : "-";

                    String cleanType = tx.type.replace("DEPOSIT_", "").replace("WITHDRAW_", "").toUpperCase();
                    String currencyName = parent.getCurrencyDisplayName(tx.currency);
                    String formattedAmount = tx.currency.equalsIgnoreCase("default")
                            ? (econ != null ? econ.format(tx.amount) : String.format("%.2f", tx.amount))
                            : String.format("%.2f %s", tx.amount, currencyName);

                    String other = (tx.otherParty != null && !tx.otherParty.isEmpty()) ? tx.otherParty : "System";

                    if (sender instanceof Player playerSender) {
                        try {
                            net.md_5.bungee.api.chat.TextComponent messageLine = new net.md_5.bungee.api.chat.TextComponent("§7[" + time + "] ");

                            net.md_5.bungee.api.chat.TextComponent typeComp = new net.md_5.bungee.api.chat.TextComponent("§f" + cleanType + " ");
                            typeComp.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                                    new net.md_5.bungee.api.chat.ComponentBuilder("§7Category: §e" + tx.category + "\n§7Raw Type: §e" + tx.type).create()));

                            net.md_5.bungee.api.chat.TextComponent amountComp = new net.md_5.bungee.api.chat.TextComponent(actionColor + actionSymbol + formattedAmount + " ");
                            amountComp.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                                    new net.md_5.bungee.api.chat.ComponentBuilder("§7Date: §e" + hoverTime).create()));

                            net.md_5.bungee.api.chat.TextComponent arrowComp = new net.md_5.bungee.api.chat.TextComponent("§8» ");

                            net.md_5.bungee.api.chat.TextComponent otherComp = new net.md_5.bungee.api.chat.TextComponent("§f" + other);
                            otherComp.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                                    new net.md_5.bungee.api.chat.ComponentBuilder("§7Target/Source Party").create()));

                            messageLine.addExtra(typeComp);
                            messageLine.addExtra(amountComp);
                            messageLine.addExtra(arrowComp);
                            messageLine.addExtra(otherComp);

                            playerSender.spigot().sendMessage(messageLine);
                        } catch (Throwable t) {
                            sender.sendMessage("§7[" + time + "] §f" + cleanType + " " + actionColor + actionSymbol + formattedAmount + " §8» §f" + other);
                        }
                    } else {
                        sender.sendMessage("§7[" + time + "] §f" + cleanType + " " + actionColor + actionSymbol + formattedAmount + " §8» §f" + other);
                    }
                }
                sender.sendMessage(parent.getMsg("commands.logs.footer", "§b§l========================================="));
            });
        });
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
