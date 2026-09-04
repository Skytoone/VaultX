package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class TopSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public TopSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "top";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("baltop", "rich");
    }

    @Override
    public String getPermission() {
        return "vault.top";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("vault.top") && !sender.hasPermission("vault.balance.top")
                && !sender.hasPermission("vault.admin")) {
            sender.sendMessage(parent.getMsg("general.no-permission", "§cYou do not have permission to execute this command!"));
            return true;
        }

        Economy econ = parent.getEconomy();
        if (econ == null) {
            sender.sendMessage(parent.getMsg("general.economy-not-found", "§cNo economy provider registered!"));
            return true;
        }

        String currency = "default";
        int page = 1;

        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
                if (page < 1) page = 1;
            } catch (NumberFormatException e) {
                currency = args[1];
                if (args.length >= 3) {
                    try {
                        page = Integer.parseInt(args[2]);
                        if (page < 1) page = 1;
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(parent.getMsg("commands.top.invalid-page", "§cInvalid page number."));
                        return true;
                    }
                }
            }
        }

        net.milkbowl.vault.redis.VaultRedisManager redis = net.milkbowl.vault.redis.VaultRedisManager.getInstance();
        if (redis == null) {
            sender.sendMessage(parent.getMsg("commands.top.redis-not-enabled",
                    "§c§l[VaultX] §cRedis is not enabled. Wealth leaderboard is unavailable."));
            return true;
        }

        List<net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry> leaderboard = redis.getLeaderboard(currency);
        List<String> excluded = parent.getPlugin().getConfig().getStringList("baltop.excluded-accounts");
        if (excluded != null && !excluded.isEmpty()) {
            List<String> lowerExcluded = excluded.stream().map(String::toLowerCase)
                    .collect(Collectors.toList());
            leaderboard = leaderboard.stream().filter(e -> e != null && !lowerExcluded.contains(e.name.toLowerCase()))
                    .collect(Collectors.toList());
        }

        boolean hideVanished = parent.getPlugin().getConfig().getBoolean("baltop.hide-vanished-players", true);
        if (hideVanished) {
            leaderboard = leaderboard.stream().filter(e -> {
                if (e == null) return false;
                Player p = Bukkit.getPlayerExact(e.name);
                if (p != null) {
                    for (org.bukkit.metadata.MetadataValue meta : p.getMetadata("vanished")) {
                        if (meta.asBoolean()) return false;
                    }
                }
                return true;
            }).collect(Collectors.toList());
        }

        if (leaderboard.isEmpty()) {
            sender.sendMessage(parent.getMsg("commands.top.no-data", "§e§l[VaultX Rich List] §cNo data found for currency '%currency%'.")
                    .replace("%currency%", currency));
            return true;
        }

        int pageSize = 10;
        int maxPages = (int) Math.ceil((double) leaderboard.size() / pageSize);
        if (page > maxPages) page = maxPages;

        String divider = parent.getMsg("commands.top.divider", "&6&m+---------------------------------------------------+");
        sender.sendMessage(divider);
        sender.sendMessage(parent.getMsg("commands.top.header", "§6§l               VaultX Rich List (%currency%)")
                .replace("%currency%", parent.getCurrencyDisplayName(currency))
                .replace("%page%", String.valueOf(page))
                .replace("%max%", String.valueOf(maxPages)));
        sender.sendMessage(divider);

        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, leaderboard.size());

        for (int i = start; i < end; i++) {
            net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry entry = leaderboard.get(i);
            String formattedBalance;
            if (currency.equalsIgnoreCase("default")) {
                formattedBalance = econ.format(entry.balance);
            } else {
                formattedBalance = String.format("%,.2f %s", entry.balance, parent.getCurrencyDisplayName(currency));
            }

            sender.sendMessage(parent.getMsg("commands.top.entry", "§e #%rank%  §f%player%        §8»  §a%amount%")
                    .replace("%rank%", String.valueOf(i + 1))
                    .replace("%player%", entry.name)
                    .replace("%amount%", formattedBalance));
        }

        sender.sendMessage(divider);
        sender.sendMessage(parent.getMsg("commands.top.footer", "§e   Page %page%/%max%  |  Use /vx top [devise] [page]")
                .replace("%currency%", parent.getCurrencyDisplayName(currency))
                .replace("%page%", String.valueOf(page))
                .replace("%max%", String.valueOf(maxPages)));
        sender.sendMessage(divider);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
