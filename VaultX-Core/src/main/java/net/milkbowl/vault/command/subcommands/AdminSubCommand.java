package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.command.subcommands.admin.AdminAccountHandler;
import net.milkbowl.vault.command.subcommands.admin.AdminBankHandler;
import net.milkbowl.vault.command.subcommands.admin.AdminSecurityHandler;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class AdminSubCommand implements SubCommand {

    private final VaultXCommand parent;
    private final AdminAccountHandler accountHandler;
    private final AdminBankHandler bankHandler;
    private final AdminSecurityHandler securityHandler;

    public AdminSubCommand(VaultXCommand parent) {
        this.parent = parent;
        this.accountHandler = new AdminAccountHandler(parent);
        this.bankHandler = new AdminBankHandler(parent);
        this.securityHandler = new AdminSecurityHandler(parent);
    }

    @Override
    public String getName() {
        return "admin";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("eco", "economy");
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
        if (econ == null) {
            sender.sendMessage(parent.getMsg("general.economy-not-found", "§cNo economy provider registered!"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(parent.getMsg("commands.admin.usage",
                    "§cUsage: /vaultx admin [give/take/set/freeze/unfreeze/audit/stats/report] ..."));
            return true;
        }

        String action = args[1].toLowerCase();

        if (action.equalsIgnoreCase("snapshot")) {
            return accountHandler.handleSnapshot(sender, args);
        }
        if (action.equalsIgnoreCase("tax")) {
            return securityHandler.handleTax(sender, args);
        }
        if (action.equalsIgnoreCase("treasury")) {
            return bankHandler.handleTreasury(sender);
        }
        if (action.equalsIgnoreCase("subvention")) {
            return bankHandler.handleSubvention(sender, args);
        }
        if (action.equalsIgnoreCase("bank")) {
            return bankHandler.handleBank(sender, args);
        }
        if (action.equalsIgnoreCase("stats")) {
            return new StatsSubCommand(parent).execute(sender, label, new String[]{"stats", "macro"});
        }
        if (action.equalsIgnoreCase("report")) {
            return new ReportSubCommand(parent).execute(sender, label, args);
        }
        if (action.equalsIgnoreCase("audit")) {
            securityHandler.handleAudit(sender, args);
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(parent.getMsg("commands.admin.usage-full",
                    "§cUsage: /vaultx admin [give/take/set/freeze/unfreeze/audit/stats/report] [player] ..."));
            return true;
        }

        OfflinePlayer target = parent.resolvePlayerFast(args[2]);
        if (target == null || (!net.milkbowl.vault.util.UUIDCache.hasPlayedBeforeFast(target) && !target.isOnline())) {
            sender.sendMessage(parent.getMsg("commands.admin.player-not-found", "§cPlayer '%player%' not found.")
                    .replace("%player%", args[2]));
            return true;
        }

        if (action.equalsIgnoreCase("freeze")) {
            return accountHandler.handleFreeze(sender, target, args[2], args);
        } else if (action.equalsIgnoreCase("unfreeze")) {
            return accountHandler.handleUnfreeze(sender, target, args[2]);
        }

        return accountHandler.handleGiveTakeSet(sender, action, target, args[2], args);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("vault.admin")) {
            return Collections.emptyList();
        }
        List<String> suggestions = new ArrayList<>();
        Economy econ = parent.getEconomy();
        if (args.length == 2) {
            suggestions.add("give");
            suggestions.add("take");
            suggestions.add("set");
            suggestions.add("freeze");
            suggestions.add("unfreeze");
            suggestions.add("bank");
            suggestions.add("audit");
            suggestions.add("stats");
            suggestions.add("report");
            return suggestions.stream().filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        } else if (args.length == 3) {
            if (args[1].equalsIgnoreCase("stats")) {
                return Collections.emptyList();
            }
            if (args[1].equalsIgnoreCase("report")) {
                return Arrays.asList("1", "7", "30").stream().filter(s -> s.startsWith(args[2].toLowerCase())).collect(Collectors.toList());
            }
            if (args[1].equalsIgnoreCase("audit")) {
                List<String> options = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
                options.add("all");
                return options.stream().filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase())).collect(Collectors.toList());
            }
            if (args[1].equalsIgnoreCase("bank")) {
                return Arrays.asList("delete", "addmember", "removemember").stream().filter(s -> s.startsWith(args[2].toLowerCase())).collect(Collectors.toList());
            }
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase())).collect(Collectors.toList());
        } else if (args.length == 4) {
            if (args[1].equalsIgnoreCase("freeze")) {
                return Arrays.asList("Duplication_abuse", "Suspicious_activity").stream().filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase())).collect(Collectors.toList());
            }
            if (args[1].equalsIgnoreCase("bank")) {
                net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
                if (fm != null) {
                    return fm.loadAllBanks().keySet().stream().filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase())).collect(Collectors.toList());
                }
            }
            return Arrays.asList("10", "100", "1000");
        } else if (args.length == 5 && econ != null) {
            return parent.getCurrencies(econ).stream().filter(s -> s.toLowerCase().startsWith(args[4].toLowerCase())).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
