package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BlackMarketSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public BlackMarketSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "blackmarket";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("launder", "marchenoir", "dirty");
    }

    @Override
    public String getPermission() {
        return "vault.command.blackmarket";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        String sub = (args.length > 0 && args[0] != null) ? args[0].toLowerCase() : label.toLowerCase();
        if (sub.equals("dirty")) {
            handleDirty(sender, args);
        } else {
            handleBlackMarket(sender, args);
        }
        return true;
    }

    private void handleBlackMarket(CommandSender sender, String[] args) {
        if (!parent.getPlugin().getConfig().getBoolean("blackmarket.enabled", true)) {
            sender.sendMessage(parent.getMsg("blackmarket.disabled", "§cThe Black Market is currently disabled on this server."));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(parent.getMsg("general.only-players", "§cOnly players can execute this command."));
            return;
        }
        Economy econ = parent.getEconomy();
        if (args.length >= 2 && args[1].equalsIgnoreCase("launder")) {
            if (args.length < 3) {
                player.sendMessage(parent.getMsg("blackmarket.insufficient-dirty", "§cUsage: /blackmarket launder <amount>"));
                return;
            }
            double amount;
            try {
                amount = parent.parsePositiveDouble(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage(parent.getMsg("general.invalid-amount", "§cInvalid amount."));
                return;
            }
            net.milkbowl.vault.economy.BlackMarketManager bm = Vault.getBlackMarketManager();
            if (bm != null) {
                net.milkbowl.vault.economy.BlackMarketManager.LaunderingResult res = bm.launder(player, amount, econ);
                if (!res.isSuccess()) {
                    player.sendMessage(parent.getMsg("blackmarket.insufficient-dirty",
                            "§cYou do not have enough dirty money to launder that amount!"));
                } else if (res.isSeized()) {
                    player.sendMessage(parent.getMsg("blackmarket.launder-seized",
                            "§c&l🚨 [POLICE RAID] §cPolice intercepted your transaction! §c%seized% §cof dirty money was confiscated!")
                            .replace("%seized%", econ != null ? econ.format(res.getDirtyLaundered()) : String.valueOf(res.getDirtyLaundered())));
                } else {
                    player.sendMessage(parent.getMsg("blackmarket.launder-success",
                            "§a&l✔ [Black Market] §aSuccessfully laundered §c%dirty% §aof dirty money! Deposited §a%clean% §aclean cash into your account (Fee: §e%fee%§a).")
                            .replace("%dirty%", econ != null ? econ.format(res.getDirtyLaundered()) : String.valueOf(res.getDirtyLaundered()))
                            .replace("%clean%", econ != null ? econ.format(res.getCleanReceived()) : String.valueOf(res.getCleanReceived()))
                            .replace("%fee%", econ != null ? econ.format(res.getFeePaid()) : String.valueOf(res.getFeePaid())));
                }
            }
            return;
        }

        if (Vault.getVaultXGUI() != null) {
            Vault.getVaultXGUI().openBlackMarket(player);
        }
    }

    private void handleDirty(CommandSender sender, String[] args) {
        net.milkbowl.vault.economy.BlackMarketManager bm = Vault.getBlackMarketManager();
        if (bm == null) return;
        Economy econ = parent.getEconomy();

        if (args.length < 2 || args[1].equalsIgnoreCase("balance")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(parent.getMsg("general.only-players", "§cOnly players can check dirty balance."));
                return;
            }
            double bal = bm.getDirtyBalance(player);
            player.sendMessage(parent.getMsg("blackmarket.dirty-balance",
                    "§c§l[Dirty Money] §fYou currently hold §c%amount% §fof dirty money.")
                    .replace("%amount%", econ != null ? econ.format(bal) : String.valueOf(bal)));
            return;
        }

        if (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("take")) {
            if (!sender.hasPermission("vault.admin")) {
                sender.sendMessage(parent.getMsg("general.no-permission", "§cNo permission."));
                return;
            }
            if (args.length < 4) {
                sender.sendMessage("§cUsage: /vx dirty give/take <player> <amount>");
                return;
            }
            OfflinePlayer target = parent.resolvePlayerFast(args[2]);
            if (target == null || !target.isOnline() || target.getPlayer() == null) {
                sender.sendMessage(parent.getMsg("general.player-not-found", "§cPlayer not found or offline."));
                return;
            }
            double amount;
            try {
                amount = parent.parsePositiveDouble(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(parent.getMsg("general.invalid-amount", "§cInvalid amount."));
                return;
            }

            if (args[1].equalsIgnoreCase("give")) {
                bm.depositDirty(target.getPlayer(), amount);
                sender.sendMessage(parent.getMsg("blackmarket.give-success",
                        "§a&l[Black Market] §fGave §c%amount% §fof dirty money to §e%player%§f.")
                        .replace("%amount%", econ != null ? econ.format(amount) : String.valueOf(amount))
                        .replace("%player%", target.getName() != null ? target.getName() : args[2]));
            } else {
                bm.withdrawDirty(target.getPlayer(), amount);
                sender.sendMessage(parent.getMsg("blackmarket.take-success",
                        "§a&l[Black Market] §fRemoved §c%amount% §fof dirty money from §e%player%§f.")
                        .replace("%amount%", econ != null ? econ.format(amount) : String.valueOf(amount))
                        .replace("%player%", target.getName() != null ? target.getName() : args[2]));
            }
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
