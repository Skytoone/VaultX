package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class CheckSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public CheckSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "check";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("cheque");
    }

    @Override
    public String getPermission() {
        return "vault.command.check";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        Economy econ = parent.getEconomy();
        if (!parent.getPlugin().getConfig().getBoolean("checks.enabled", true)) {
            sender.sendMessage(parent.getMsg("commands.check.disabled", "§cPhysical checks are disabled on this server."));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(parent.getMsg("commands.check.only-players", "§cOnly players can sign physical checks."));
            return true;
        }

        if (args.length < 3 || !args[1].equalsIgnoreCase("write")) {
            player.sendMessage(parent.getMsg("commands.check.usage", "§cUsage: /vaultx check write <amount> [currency]"));
            return true;
        }

        double amount;
        try {
            amount = parent.parsePositiveDouble(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(parent.getMsg("commands.check.invalid-amount", "§cInvalid amount. It must be a positive number."));
            return true;
        }

        double minAmount = parent.getPlugin().getConfig().getDouble("checks.min-amount", 1.0);
        double maxAmount = parent.getPlugin().getConfig().getDouble("checks.max-amount", 10000000.0);
        double creationFee = parent.getPlugin().getConfig().getDouble("checks.creation-fee", 10.0);

        if (amount < minAmount) {
            player.sendMessage(parent.getMsg("commands.check.min-amount", "§cThe check amount must be at least %amount%!")
                    .replace("%amount%", econ != null ? econ.format(minAmount) : String.valueOf(minAmount)));
            return true;
        }
        if (maxAmount > 0 && amount > maxAmount) {
            player.sendMessage(parent.getMsg("commands.check.max-amount", "§cThe check amount cannot exceed %amount%!")
                    .replace("%amount%", econ != null ? econ.format(maxAmount) : String.valueOf(maxAmount)));
            return true;
        }

        String currency = args.length >= 4 ? args[3] : "default";

        double totalRequired = amount + (currency.equalsIgnoreCase("default") ? creationFee : 0);
        double balance = 0;
        if (currency.equalsIgnoreCase("default")) {
            balance = econ != null ? econ.getBalance(player) : 0;
        } else if (econ instanceof MultiCurrencyEconomy) {
            balance = ((MultiCurrencyEconomy) econ).getCurrencyBalance(player, currency);
        } else {
            player.sendMessage(parent.getMsg("commands.check.unsupported-currency", "§cThis currency is not supported."));
            return true;
        }

        if (balance < totalRequired) {
            player.sendMessage(parent.getMsg("commands.check.insufficient-funds",
                    "§cYou do not have enough funds to sign this check (including creation fees)."));
            return true;
        }

        EconomyResponse wRes;
        if (currency.equalsIgnoreCase("default")) {
            wRes = econ.withdrawPlayer(player, totalRequired);
        } else {
            wRes = ((MultiCurrencyEconomy) econ).withdrawCurrencyPlayer(player, currency, amount);
        }

        if (!wRes.transactionSuccess()) {
            player.sendMessage(parent.getMsg("commands.check.withdraw-failed", "§cFailed to withdraw funds for the check."));
            return true;
        }

        String checkId = UUID.randomUUID().toString();
        net.milkbowl.vault.redis.LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm != null) {
            fm.saveCheck(checkId, amount, currency, player.getUniqueId());
        }

        ItemStack item = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6§lEconomic Check");
            List<String> lore = new ArrayList<>();
            String displayVal = currency.equalsIgnoreCase("default")
                    ? (econ != null ? econ.format(amount) : String.valueOf(amount))
                    : String.format("%.2f %s", amount, parent.getCurrencyDisplayName(currency));
            lore.add("§7Value: §e" + displayVal);
            lore.add("§7Signed by: §f" + player.getName());
            lore.add("§7Réseau : §d§lVaultX Verified Check");
            lore.add("§8ID: " + checkId);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (leftover != null && !leftover.isEmpty()) {
            for (ItemStack leftoverItem : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem);
            }
        }
        String formatted = currency.equalsIgnoreCase("default")
                ? (econ != null ? econ.format(amount) : String.valueOf(amount))
                : String.format("%.2f %s", amount, parent.getCurrencyDisplayName(currency));
        player.sendMessage(parent.getMsg("commands.check.write-success", "§a§l✔ §aYou signed a check of §e%amount%&a!")
                .replace("%amount%", formatted));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
