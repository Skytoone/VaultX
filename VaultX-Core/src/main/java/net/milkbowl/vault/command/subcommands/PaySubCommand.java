package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class PaySubCommand implements SubCommand {

    private final VaultXCommand parent;

    public PaySubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "pay";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.command.pay";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(parent.getMsg("general.only-players", "§cOnly players can execute this command."));
            return true;
        }

        Economy econ = parent.getEconomy();
        if (econ == null) {
            sender.sendMessage(parent.getMsg("general.economy-not-found", "§cNo economy provider registered!"));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(parent.getMsg("commands.pay.usage", "&cUsage: /vaultx pay <player> <amount> [currency]"));
            return true;
        }

        OfflinePlayer target = parent.resolvePlayerFast(args[1]);
        if (target == null || (!net.milkbowl.vault.util.UUIDCache.hasPlayedBeforeFast(target) && !target.isOnline())) {
            sender.sendMessage(parent.getMsg("general.player-not-found", "§cPlayer not found."));
            return true;
        }

        if (player.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(parent.getMsg("commands.pay.self-pay", "&cYou cannot send money to yourself."));
            return true;
        }

        double amount;
        try {
            amount = parent.parsePositiveDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(parent.getMsg("general.invalid-amount", "&cInvalid amount. It must be a positive number."));
            return true;
        }

        String currency = args.length >= 4 ? args[3] : "default";

        double senderBalance;
        if (currency.equalsIgnoreCase("default")) {
            senderBalance = econ.getBalance(player);
        } else if (econ instanceof MultiCurrencyEconomy) {
            senderBalance = ((MultiCurrencyEconomy) econ).getCurrencyBalance(player, currency);
        } else {
            sender.sendMessage(parent.getMsg("general.economy-not-found", "§cNo economy provider is registered!"));
            return true;
        }

        if (senderBalance < amount) {
            sender.sendMessage(parent.getMsg("commands.pay.insufficient-funds", "&cYou do not have enough funds."));
            return true;
        }

        double tax = 0;
        double depositAmount = amount;
        net.milkbowl.vault.economy.CentralBankManager cb = Vault.getCentralBankManager();
        if (cb != null && cb.isTaxesEnabled()) {
            double taxPct = cb.getPayTaxPercent();
            if (taxPct > 0) {
                tax = amount * (taxPct / 100.0);
                depositAmount = amount - tax;
            }
        }

        EconomyResponse withdrawRes;
        boolean targetOnline = target.isOnline() && target.getPlayer() != null;

        if (currency.equalsIgnoreCase("default")) {
            withdrawRes = econ.withdrawPlayer(player, amount);
            if (withdrawRes.transactionSuccess()) {
                if (targetOnline) {
                    EconomyResponse depositRes = econ.depositPlayer(target, depositAmount);
                    if (!depositRes.transactionSuccess()) {
                        econ.depositPlayer(player, amount);
                        sender.sendMessage(parent.getMsg("commands.pay.deposit-failed",
                                "§cFailed to deposit funds into recipient's account."));
                        return true;
                    }
                } else {
                    Vault.getMailboxManager().sendOfflineMail(target.getUniqueId(), player.getName(),
                            "Payment received from " + player.getName(), depositAmount, "default");
                }

                if (tax > 0 && cb != null) {
                    econ.bankDeposit(cb.getTreasuryAccount(), tax);
                }

                if (Vault.getFirewall() != null) {
                    Vault.getFirewall().recordTransfer(player, target, amount);
                }
            } else {
                sender.sendMessage(parent.getMsg("commands.pay.tx-failed", "&cTransaction failed: %error%")
                        .replace("%error%", withdrawRes.errorMessage));
                return true;
            }
        } else if (econ instanceof MultiCurrencyEconomy mcEcon) {
            withdrawRes = mcEcon.withdrawCurrencyPlayer(player, currency, amount);
            if (withdrawRes.transactionSuccess()) {
                if (targetOnline) {
                    EconomyResponse depositRes = mcEcon.depositCurrencyPlayer(target, currency, depositAmount);
                    if (!depositRes.transactionSuccess()) {
                        mcEcon.depositCurrencyPlayer(player, currency, amount);
                        sender.sendMessage(parent.getMsg("commands.pay.deposit-failed",
                                "§cFailed to deposit funds into recipient's account."));
                        return true;
                    }
                } else {
                    Vault.getMailboxManager().sendOfflineMail(target.getUniqueId(), player.getName(),
                            "Payment received from " + player.getName(), depositAmount, currency);
                }

                if (tax > 0 && cb != null) {
                    double rate = Vault.getExchangeRateManager().getRate(currency);
                    double taxInDefault = tax * rate;
                    econ.bankDeposit(cb.getTreasuryAccount(), taxInDefault);
                }

                if (Vault.getFirewall() != null) {
                    double rate = Vault.getExchangeRateManager().getRate(currency);
                    double amountInDefault = amount * rate;
                    Vault.getFirewall().recordTransfer(player, target, amountInDefault);
                }
            } else {
                sender.sendMessage(parent.getMsg("commands.pay.tx-failed", "&cTransaction failed: %error%")
                        .replace("%error%", withdrawRes.errorMessage));
                return true;
            }
        } else {
            sender.sendMessage(parent.getMsg("general.economy-not-found", "§cNo economy provider is registered!"));
            return true;
        }

        String formattedAmount = currency.equalsIgnoreCase("default") ? econ.format(amount)
                : String.format("%.2f %s", amount, parent.getCurrencyDisplayName(currency));
        String formattedTax = String.format("%.2f %s", tax, parent.getCurrencyDisplayName(currency));

        if (tax > 0) {
            String msg = parent.getMsg("commands.pay.success-sender",
                    "&a&l✔ &aYou sent &e%amount% &ato &e%receiver%&a.")
                    .replace("%amount%", formattedAmount)
                    .replace("%receiver%", target.getName() != null ? target.getName() : "Unknown");
            String taxMsg = parent.getMsg("commands.pay.fee-applied", " &7(including &e%fee% &7tax)")
                    .replace("%fee%", formattedTax);
            sender.sendMessage(msg + taxMsg);
        } else {
            sender.sendMessage(parent.getMsg("commands.pay.success-sender", "&a&l✔ &aYou sent &e%amount% &ato &e%receiver%&a.")
                    .replace("%amount%", formattedAmount)
                    .replace("%receiver%", target.getName() != null ? target.getName() : "Unknown"));
        }

        if (targetOnline && target.getPlayer() != null) {
            String formattedDep = currency.equalsIgnoreCase("default") ? econ.format(depositAmount)
                    : String.format("%.2f %s", depositAmount, parent.getCurrencyDisplayName(currency));
            target.getPlayer().sendMessage(parent.getMsg("commands.pay.success-receiver",
                    "&a&l✔ &aYou received &e%amount% &afrom &e%sender%&a.")
                    .replace("%amount%", formattedDep)
                    .replace("%sender%", player.getName()));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        Economy econ = parent.getEconomy();
        if (args.length == 2) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        } else if (args.length == 3) {
            return Arrays.asList("10", "100", "1000");
        } else if (args.length == 4 && econ != null) {
            return parent.getCurrencies(econ).stream()
                    .filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
