package net.milkbowl.vault.command;

import org.bukkit.command.CommandSender;

public class CommandHelpRenderer {

    private final VaultXCommand command;

    public CommandHelpRenderer(VaultXCommand command) {
        this.command = command;
    }

    public void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(
                command.getMsg("commands.help.header", "§b§l=== VaultX Command Help ===").replace("%label%", label));
        sender.sendMessage(
                command.getMsg("commands.help.menu", "§e/%label% menu §7- Open the interactive dashboard (GUI)")
                        .replace("%label%", label));
        sender.sendMessage(command.getMsg("commands.help.balance", "§e/%label% balance [player] [currency] §7- View balance")
                .replace("%label%", label));
        sender.sendMessage(
                command.getMsg("commands.help.pay", "§e/%label% pay <player> <amount> [currency] §7- Make a payment")
                        .replace("%label%", label));
        sender.sendMessage(command.getMsg("commands.help.exchange",
                "§e/%label% exchange <from> <to> <amount> §7- Convert currency").replace("%label%", label));
        sender.sendMessage(
                command.getMsg("commands.help.mailbox", "§e/%label% mailbox [claim/list] §7- Manage your mailbox")
                        .replace("%label%", label));
        sender.sendMessage(command.getMsg("commands.help.bank", "§e/%label% bank §7- Manage your shared bank accounts")
                .replace("%label%", label));
        sender.sendMessage(
                command.getMsg("commands.help.top", "§e/%label% top [currency] [page] §7- View wealth leaderboard")
                        .replace("%label%", label));
        sender.sendMessage(
                command.getMsg("commands.help.transactions", "§e/%label% transactions [page] §7- View your transaction history")
                        .replace("%label%", label));
        sender.sendMessage(command.getMsg("commands.help.escrow",
                "§e/%label% escrow [start/release/refund/list] §7- Secure transactions (Escrow)")
                .replace("%label%", label));
        if (sender.hasPermission("vault.admin")) {
            sender.sendMessage(
                    command.getMsg("commands.help.admin-header", "§d-- Admin Commands --").replace("%label%", label));
            sender.sendMessage(command.getMsg("commands.help.admin-give",
                    "§d/%label% admin give <player> <amount> <currency> §7- Give money")
                    .replace("%label%", label));
            sender.sendMessage(command.getMsg("commands.help.admin-take",
                    "§d/%label% admin take <player> <amount> <currency> §7- Take money")
                    .replace("%label%", label));
            sender.sendMessage(command.getMsg("commands.help.admin-set",
                    "§d/%label% admin set <player> <amount> <currency> §7- Set balance").replace("%label%", label));
            sender.sendMessage(command.getMsg("commands.help.admin-freeze",
                    "§d/%label% admin freeze <player> [reason] §7- Freeze transactions").replace("%label%", label));
            sender.sendMessage(command.getMsg("commands.help.admin-unfreeze",
                    "§d/%label% admin unfreeze <player> §7- Unfreeze transactions").replace("%label%", label));
            sender.sendMessage(command.getMsg("commands.help.admin-bank",
                    "§d/%label% admin bank [delete/addmember/removemember] §7- Force manage banks")
                    .replace("%label%", label));
            sender.sendMessage(command.getMsg("commands.help.admin-audit",
                    "§d/%label% admin audit [player/all] [page] §7- View security audits")
                    .replace("%label%", label));
            sender.sendMessage(
                    command.getMsg("commands.help.admin-stats", "§d/%label% admin stats §7- Telemetry and performance")
                            .replace("%label%", label));
            sender.sendMessage(command.getMsg("commands.help.admin-report",
                    "§d/%label% admin report [days] §7- Inflation analysis report").replace("%label%", label));
        }
        sender.sendMessage(
                command.getMsg("commands.help.footer", "§b§l================================").replace("%label%", label));
    }
}
