package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

public class StakingSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public StakingSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "staking";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.command.staking";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (Vault.getStakingManager() == null) {
            sender.sendMessage("§cStaking feature is not initialized.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vaultx staking <create|claim|list> ...");
            return true;
        }
        Economy econ = parent.getEconomy();
        String sub = args[1].toLowerCase();

        if (sub.equals("create")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can create stakes.");
                return true;
            }
            if (args.length < 5) {
                sender.sendMessage("§cUsage: /vaultx staking create <currency> <amount> <durationDays>");
                return true;
            }
            String curr = args[2];
            double amount = Double.parseDouble(args[3]);
            int days = Integer.parseInt(args[4]);
            Vault.getStakingManager().createStakeAsync(player, curr, amount, days,
                    (p, c) -> (econ instanceof MultiCurrencyEconomy m) ? m.getCurrencyBalance(p, c)
                            : (c.equalsIgnoreCase("default") && econ != null ? econ.getBalance(p) : 0.0),
                    (p, c, a) -> (econ instanceof MultiCurrencyEconomy m) ? m.withdrawCurrencyPlayer(p, c, a)
                            : (c.equalsIgnoreCase("default") && econ != null ? econ.withdrawPlayer(p, a)
                                    : new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Not supported")),
                    ForkJoinPool.commonPool()).thenAccept(res -> {
                        parent.runSync(() -> sender.sendMessage(
                                res.transactionSuccess() ? "§a" + res.errorMessage : "§c" + res.errorMessage));
                    });
        } else if (sub.equals("claim")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can claim stakes.");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /vaultx staking claim <depositId>");
                return true;
            }
            String depositId = args[2];
            Vault.getStakingManager().claimStakeAsync(player, depositId,
                    (p, c, a) -> (econ instanceof MultiCurrencyEconomy m) ? m.depositCurrencyPlayer(p, c, a)
                            : (c.equalsIgnoreCase("default") && econ != null ? econ.depositPlayer(p, a)
                                    : new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Not supported")),
                    ForkJoinPool.commonPool()).thenAccept(res -> {
                        parent.runSync(() -> sender.sendMessage(
                                res.transactionSuccess() ? "§aStake claimed!" : "§c" + res.errorMessage));
                    });
        } else if (sub.equals("list")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can list stakes.");
                return true;
            }
            Vault.getStakingManager().getActiveStakesAsync(player, ForkJoinPool.commonPool())
                    .thenAccept(stakes -> {
                        parent.runSync(() -> {
                            sender.sendMessage("§e=== Your Active Stakes ===");
                            for (var s : stakes) {
                                sender.sendMessage("§7" + s.depositId() + " §8| §a" + s.principal() + " " + s.currency()
                                        + " §8| Rate: " + s.interestRate());
                            }
                        });
                    });
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
