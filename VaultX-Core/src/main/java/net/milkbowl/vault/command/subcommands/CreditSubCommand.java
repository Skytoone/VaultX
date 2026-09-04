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
import java.util.concurrent.ForkJoinPool;

public class CreditSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public CreditSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "credit";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.command.credit";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (Vault.getCreditManager() == null) {
            sender.sendMessage("§cCredit feature is not initialized.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vaultx credit <score|limit> ...");
            return true;
        }
        String sub = args[1].toLowerCase();
        if (sub.equals("score")) {
            OfflinePlayer target = (args.length > 2) ? parent.resolvePlayerFast(args[2])
                    : (sender instanceof Player ? (Player) sender : null);
            if (target == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }
            Vault.getCreditManager().updateCreditScoreAsync(target, p -> {
                Economy econ = parent.getEconomy();
                return econ != null ? econ.getBalance(p) : 0.0;
            }, ForkJoinPool.commonPool()).thenAccept(score -> {
                parent.runSync(() -> sender.sendMessage("§aCredit Score for " + target.getName() + ": §e" + score));
            });
        } else if (sub.equals("limit")) {
            if (!sender.hasPermission("vault.admin")) {
                sender.sendMessage("§cYou do not have permission.");
                return true;
            }
            if (args.length < 5) {
                sender.sendMessage("§cUsage: /vaultx credit limit <player> <currency> <limit>");
                return true;
            }
            OfflinePlayer target = parent.resolvePlayerFast(args[2]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }
            String curr = args[3];
            double limit = Double.parseDouble(args[4]);
            Vault.getCreditManager()
                    .setOverdraftLimitAsync(target, curr, limit, ForkJoinPool.commonPool())
                    .thenAccept(res -> {
                        parent.runSync(() -> sender.sendMessage(
                                res.transactionSuccess() ? "§a" + res.errorMessage : "§c" + res.errorMessage));
                    });
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
