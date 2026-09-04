package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class BalanceSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public BalanceSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "balance";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("bal", "money");
    }

    @Override
    public String getPermission() {
        return "vault.command.money";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        Economy econ = parent.getEconomy();
        if (econ == null) {
            sender.sendMessage(parent.getMsg("general.economy-not-found", "§cNo economy provider registered!"));
            return true;
        }

        OfflinePlayer target;
        String currency = "default";
        List<String> currencies = parent.getCurrencies(econ);

        if (args.length == 1) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(parent.getMsg("general.only-players", "§cOnly players can execute this command."));
                return true;
            }
            target = (Player) sender;
        } else if (args.length == 2) {
            String matchedCurrency = null;
            for (String curr : currencies) {
                if (curr.equalsIgnoreCase(args[1])) {
                    matchedCurrency = curr;
                    break;
                }
            }
            if (matchedCurrency != null) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(parent.getMsg("general.only-players", "§cOnly players can execute this command."));
                    return true;
                }
                target = (Player) sender;
                currency = matchedCurrency;
            } else {
                target = parent.resolvePlayerFast(args[1]);
            }
        } else {
            target = parent.resolvePlayerFast(args[1]);
            currency = args[2];
        }

        if (target == null || (!net.milkbowl.vault.util.UUIDCache.hasPlayedBeforeFast(target) && !target.isOnline())) {
            sender.sendMessage(parent.getMsg("general.player-not-found", "§cPlayer not found."));
            return true;
        }

        if (!currency.equalsIgnoreCase("default")) {
            boolean supported = false;
            for (String curr : currencies) {
                if (curr.equalsIgnoreCase(currency)) {
                    supported = true;
                    currency = curr;
                    break;
                }
            }
            if (!supported) {
                sender.sendMessage(parent.getMsg("commands.check.unsupported-currency", "§cThis currency is not supported."));
                return true;
            }
        }

        double balance;
        if (currency.equalsIgnoreCase("default")) {
            balance = econ.getBalance(target);
        } else if (econ instanceof MultiCurrencyEconomy) {
            balance = ((MultiCurrencyEconomy) econ).getCurrencyBalance(target, currency);
        } else {
            sender.sendMessage(parent.getMsg("general.economy-not-found", "§cNo economy provider is registered!"));
            return true;
        }

        String formatted;
        if (currency.equalsIgnoreCase("default")) {
            formatted = econ.format(balance);
        } else {
            formatted = String.format("%.2f %s", balance, parent.getCurrencyDisplayName(currency));
        }

        if (target.getUniqueId().equals(((sender instanceof Player) ? ((Player) sender).getUniqueId() : null))) {
            sender.sendMessage(parent.getMsg("commands.balance.current", "&fYour current balance is: &e%amount%")
                    .replace("%amount%", formatted));
        } else {
            sender.sendMessage(parent.getMsg("commands.balance.other", "&fPlayer &e%player%&f's balance: &e%amount%")
                    .replace("%player%", parent.getPlayerNameSafe(target, args.length > 1 ? args[1] : "Unknown"))
                    .replace("%amount%", formatted));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        Economy econ = parent.getEconomy();
        if (args.length == 2) {
            List<String> options = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
            if (econ != null) options.addAll(parent.getCurrencies(econ));
            return options.stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        } else if (args.length == 3 && econ != null) {
            return parent.getCurrencies(econ).stream().filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase())).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
