package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class DynamicPricingSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public DynamicPricingSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "dynamicpricing";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("dp", "pricing");
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
        net.milkbowl.vault.economy.DynamicPricingManager dpManager = Vault.getDynamicPricingManager();
        if (dpManager == null) {
            sender.sendMessage(parent.getMsg("commands.dynamicpricing.not-active", "§c[VaultX] Dynamic Pricing system is not active."));
            return true;
        }

        if (args.length <= 1 || args[1].equalsIgnoreCase("info") || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(parent.getMsg("commands.dynamicpricing.header", "§6━━━━━ §e⚡ VaultX Dynamic Pricing Engine §6━━━━━"));
            sender.sendMessage("§7Status: " + (dpManager.isEnabled() ? "§aEnabled" : "§cDisabled"));
            sender.sendMessage("§7Elasticity Factor: §e" + dpManager.getElasticity());
            sender.sendMessage("§7Multiplier Bounds: §e" + dpManager.getMinMultiplier() + "x §7- §e"
                    + dpManager.getMaxMultiplier() + "x");
            sender.sendMessage("§7Invert Sell Prices: §e" + dpManager.isInvertSellPrices());

            List<String> currencies = parent.getCurrencies(econ);
            for (String currency : currencies) {
                String cKey = currency.toLowerCase();
                double currentSupply = dpManager.getCurrentMoneySupply(cKey);
                double baselineSupply = dpManager.getBaselineMoneySupply(cKey);
                double multiplier = dpManager.getInflationMultiplier(cKey);
                double pct = (multiplier - 1.0) * 100.0;
                String pctStr = String.format(Locale.US, "%+.1f%%", pct);

                sender.sendMessage("§8 » §f" + currency.toUpperCase() + ":");
                sender.sendMessage("§7   Current Money Supply: §e" + (econ != null ? econ.format(currentSupply)
                        : String.format(Locale.US, "%.2f", currentSupply)));
                sender.sendMessage("§7   Baseline Money Supply: §e" + (econ != null ? econ.format(baselineSupply)
                        : String.format(Locale.US, "%.2f", baselineSupply)));
                sender.sendMessage("§7   Inflation Index: §b" + String.format(Locale.US, "%.2fx", multiplier)
                        + " §8(" + (pct >= 0 ? "§c" : "§a") + pctStr + "§8)");
            }
            sender.sendMessage("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            return true;
        }

        String sub = args[1].toLowerCase();
        if (sub.equals("recalculate") || sub.equals("recalc") || sub.equals("refresh")) {
            dpManager.recalculateAllAsync();
            sender.sendMessage(parent.getMsg("commands.dynamicpricing.recalc-triggered",
                    "§a[VaultX] Dynamic pricing money supply recalculation triggered asynchronously."));
            return true;
        }

        if (sub.equals("setbase") || sub.equals("setbaseline")) {
            if (args.length < 3) {
                sender.sendMessage(parent.getMsg("commands.dynamicpricing.setbase-usage",
                        "§cUsage: /vaultx dp setbase <amount> [currency]"));
                return true;
            }
            try {
                double amount = parent.parsePositiveDouble(args[2]);
                String currency = args.length >= 4 ? args[3].toLowerCase() : "default";
                dpManager.setBaselineMoneySupply(currency, amount);
                String formatted = econ != null ? econ.format(amount) : String.valueOf(amount);
                sender.sendMessage(parent.getMsg("commands.dynamicpricing.setbase-success",
                        "§a[VaultX] Baseline money supply for §e%currency% §aset to §e%amount%§a.")
                        .replace("%currency%", currency)
                        .replace("%amount%", formatted));
            } catch (NumberFormatException e) {
                sender.sendMessage(parent.getMsg("commands.dynamicpricing.invalid-amount", "§cInvalid amount specified."));
            }
            return true;
        }

        if (sub.equals("calc") || sub.equals("calculate") || sub.equals("test")) {
            if (args.length < 3) {
                sender.sendMessage(parent.getMsg("commands.dynamicpricing.calc-usage",
                        "§cUsage: /vaultx dp calc <basePrice> [currency]"));
                return true;
            }
            try {
                double basePrice = parent.parsePositiveDouble(args[2]);
                String currency = args.length >= 4 ? args[3].toLowerCase() : "default";
                double adjustedBuy = dpManager.getAdjustedBuyPrice(currency, basePrice);
                double adjustedSell = dpManager.getAdjustedSellPrice(currency, basePrice);
                double mult = dpManager.getInflationMultiplier(currency);

                sender.sendMessage("§6[VaultX Dynamic Pricing Simulation] §f(" + currency.toUpperCase() + " @ "
                        + String.format(Locale.US, "%.2fx", mult) + "):");
                sender.sendMessage("§7  Base Price: §e" + (econ != null ? econ.format(basePrice) : basePrice));
                sender.sendMessage("§7  Adjusted Buy Price: §a" + (econ != null ? econ.format(adjustedBuy) : adjustedBuy));
                sender.sendMessage("§7  Adjusted Sell Price: §c" + (econ != null ? econ.format(adjustedSell) : adjustedSell));
            } catch (NumberFormatException e) {
                sender.sendMessage(parent.getMsg("commands.dynamicpricing.invalid-amount", "§cInvalid base price specified."));
            }
            return true;
        }

        sender.sendMessage(parent.getMsg("commands.dynamicpricing.unknown-subcommand",
                "§cUnknown subcommand. Use: /vx dp [info|recalculate|setbase|calc]"));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
