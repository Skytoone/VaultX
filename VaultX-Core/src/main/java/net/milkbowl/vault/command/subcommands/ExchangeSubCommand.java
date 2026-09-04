package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.ExchangeRateManager.ConversionResult;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ExchangeSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public ExchangeSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "exchange";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("convert");
    }

    @Override
    public String getPermission() {
        return "vault.command.exchange";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("vault.convert") && !sender.hasPermission("vault.exchange")
                && !sender.hasPermission("vault.admin")) {
            sender.sendMessage(parent.getMsg("general.no-permission", "§cYou do not have permission to execute this command!"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            if (args.length == 2 && args[1].equalsIgnoreCase("rates")) {
                sender.sendMessage(parent.getMsg("commands.exchange.rates-header", "§b§l=== Current Exchange Rates ==="));
                Map<String, Double> rates = Vault.getExchangeRateManager().getRates();
                for (Map.Entry<String, Double> entry : rates.entrySet()) {
                    sender.sendMessage(parent.getMsg("commands.exchange.rates-entry", "  §f1 §e%from% §7= §a%rate% §7(Base Currency)")
                            .replace("%from%", entry.getKey().toUpperCase())
                            .replace("%rate%", String.format("%.4f", entry.getValue())));
                }
                sender.sendMessage(parent.getMsg("commands.exchange.rates-footer", "§b§l==============================="));
                return true;
            }
            sender.sendMessage(parent.getMsg("commands.exchange.only-players-convert",
                    "§cOnly players can perform currency conversions."));
            return true;
        }

        if (args.length < 4) {
            if (args.length == 2 && args[1].equalsIgnoreCase("rates")) {
                sender.sendMessage(parent.getMsg("commands.exchange.rates-header", "§b§l=== Current Exchange Rates ==="));
                Map<String, Double> rates = Vault.getExchangeRateManager().getRates();
                for (Map.Entry<String, Double> entry : rates.entrySet()) {
                    sender.sendMessage(parent.getMsg("commands.exchange.rates-entry", "  §f1 §e%from% §7= §a%rate% §7(Base Currency)")
                            .replace("%from%", entry.getKey().toUpperCase())
                            .replace("%rate%", String.format("%.4f", entry.getValue())));
                }
                sender.sendMessage(parent.getMsg("commands.exchange.rates-footer", "§b§l==============================="));
                return true;
            }
            if (Vault.getVaultXGUI() != null) {
                Vault.getVaultXGUI().openExchange(player);
            } else {
                String cmdWord = args[0].toLowerCase();
                sender.sendMessage(parent.getMsg("commands.exchange.rates-usage",
                        "§cUsage: /vaultx " + cmdWord + " <from> <to> <amount> or rates"));
            }
            return true;
        }

        double amount;
        try {
            amount = parent.parsePositiveDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(parent.getMsg("general.invalid-amount", "§cInvalid amount. It must be a positive number."));
            return true;
        }

        sender.sendMessage(parent.getMsg("commands.exchange.converting", "§eConverting..."));
        final String fromCurrency = args[1];
        final String toCurrency = args[2];
        final double finalAmount = amount;
        ConversionResult res = Vault.getExchangeRateManager().convert(player, fromCurrency, toCurrency, finalAmount);
        if (res != null && res.success) {
            sender.sendMessage(parent.getMsg("commands.exchange.success", "§a§l✔ §aConversion successful: %message%")
                    .replace("%message%", res.message));
            net.milkbowl.vault.util.VaultXVisuals.sendTransactionNotification(player, toCurrency, res.receivedAmount, true);
        } else {
            sender.sendMessage(parent.getMsg("commands.exchange.failure", "§c§l❌ §cConversion failed: %message%")
                    .replace("%message%", res != null ? res.message : "Error"));
            net.milkbowl.vault.util.VaultXVisuals.playFailureSound(player);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        Economy econ = parent.getEconomy();
        if (args.length == 2 && econ != null) {
            List<String> options = new ArrayList<>(parent.getCurrencies(econ));
            options.add("rates");
            return options.stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        } else if (args.length == 3 && econ != null) {
            return parent.getCurrencies(econ).stream().filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase())).collect(Collectors.toList());
        } else if (args.length == 4) {
            return Arrays.asList("10", "100", "1000");
        }
        return Collections.emptyList();
    }
}
