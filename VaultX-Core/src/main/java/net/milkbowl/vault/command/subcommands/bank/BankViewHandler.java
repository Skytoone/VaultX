package net.milkbowl.vault.command.subcommands.bank;

import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class BankViewHandler {

    private final VaultXCommand parent;

    public BankViewHandler(VaultXCommand parent) {
        this.parent = parent;
    }

    public boolean handleList(CommandSender sender, Player player, net.milkbowl.vault.redis.LocalFailoverManager fm) {
        Economy econ = parent.getEconomy();
        parent.runAsync(() -> {
            List<String> bankNames = fm.getBanksForPlayer(player.getUniqueId());
            parent.runSync(() -> {
                if (bankNames.isEmpty()) {
                    sender.sendMessage(parent.getMsg("bank.no-banks", "§cYou are not part of any shared bank account."));
                    return;
                }
                sender.sendMessage(parent.getMsg("bank.list-header", "§b§l=== Your Shared Bank Accounts ==="));
                for (String bName : bankNames) {
                    String role = fm.getBankRole(bName, player.getUniqueId());
                    double balance = econ.bankBalance(bName).balance;
                    if (balance == 0 && !econ.bankBalance(bName).transactionSuccess()) {
                        balance = fm.getBankBalance(bName);
                    }
                    sender.sendMessage(parent.getMsg("bank.list-entry", "  §7- §e%name% §7| Role: §f%role% §7| Balance: §a%amount%")
                            .replace("%name%", bName)
                            .replace("%role%", role)
                            .replace("%amount%", econ.format(balance)));
                }
                sender.sendMessage(parent.getMsg("bank.list-footer", "§b§l========================================"));
            });
        });
        return true;
    }

    public void handleBalance(CommandSender sender, String bankName, net.milkbowl.vault.redis.LocalFailoverManager fm) {
        Economy econ = parent.getEconomy();
        sender.sendMessage(parent.getMsg("bank.fetching-balance", "§eRetrieving balance..."));
        parent.runAsync(() -> {
            double balance = econ.bankBalance(bankName).balance;
            if (balance == 0 && !econ.bankBalance(bankName).transactionSuccess()) {
                balance = fm.getBankBalance(bankName);
            }
            final double finalBalance = balance;
            parent.runSync(() -> {
                sender.sendMessage(parent.getMsg("bank.balance", "§a§l[Bank] §aAccount balance for §e%name%§a: §e%amount%")
                        .replace("%name%", bankName)
                        .replace("%amount%", econ.format(finalBalance)));
            });
        });
    }
}
