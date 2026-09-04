package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PayOfflineSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public PayOfflineSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "payoffline";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.admin.payoffline";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("vault.admin.payoffline")) {
            sender.sendMessage("§cYou do not have permission!");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /vaultx payoffline <player> <currency> <amount> [reason]");
            return true;
        }
        OfflinePlayer target = parent.resolvePlayerFast(args[1]);
        if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
            sender.sendMessage("§cPlayer not found: " + args[1]);
            return true;
        }
        String currency = args[2];
        double amount;
        try {
            amount = parent.parsePositiveDouble(args[3]);
        } catch (Exception e) {
            sender.sendMessage("§cInvalid amount! Must be a positive number.");
            return true;
        }
        String reason = args.length > 4 ? String.join(" ", Arrays.copyOfRange(args, 4, args.length))
                : "Admin Payout";

        Vault.getMailboxAPI().sendOfflinePaymentAsync(target, currency, amount, reason).thenAccept(success -> {
            parent.runSync(() -> {
                if (success) {
                    sender.sendMessage("§aSuccessfully sent offline payment of " + amount + " " + currency + " to "
                            + target.getName() + "!");
                } else {
                    sender.sendMessage("§cFailed to send offline payment.");
                }
            });
        });
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
