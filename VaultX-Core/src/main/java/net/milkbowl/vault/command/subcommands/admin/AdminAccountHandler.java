package net.milkbowl.vault.command.subcommands.admin;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.Arrays;

public class AdminAccountHandler {

    private final VaultXCommand parent;

    public AdminAccountHandler(VaultXCommand parent) {
        this.parent = parent;
    }

    public boolean handleSnapshot(CommandSender sender, String[] args) {
        net.milkbowl.vault.economy.VaultSnapshotAPI snapshotAPI = Vault.getSnapshotAPI();
        if (snapshotAPI == null) {
            sender.sendMessage("§cSnapshot API is not available.");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /vaultx admin snapshot <create [label] | list | rollback <id> [player] | delete <id>>");
            return true;
        }

        String sub = args[2].toLowerCase();
        if (sub.equals("create")) {
            String snapLabel = args.length >= 4
                    ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                    : "Manual Admin Snapshot";
            sender.sendMessage("§eCreating atomic economy snapshot...");
            snapshotAPI.createSnapshotAsync(snapLabel).thenAccept(snap -> {
                sender.sendMessage(
                        "§a✔ Snapshot created! ID: §e" + snap.snapshotId() + " §7(" + snap.totalAccountsCaptured()
                                + " accounts, Net Worth: $" + String.format("%.2f", snap.totalNetWorth()) + ")");
            });
        } else if (sub.equals("list")) {
            snapshotAPI.getSnapshotsAsync(10).thenAccept(list -> {
                if (list.isEmpty()) {
                    sender.sendMessage("§cNo snapshots found.");
                    return;
                }
                sender.sendMessage("§6--- VaultX Economy Snapshots ---");
                for (var snap : list) {
                    sender.sendMessage("§e" + snap.snapshotId() + " §7- " + snap.label() + " §8("
                            + snap.totalAccountsCaptured() + " accounts, $"
                            + String.format("%.2f", snap.totalNetWorth()) + ")");
                }
            });
        } else if (sub.equals("rollback")) {
            if (args.length < 4) {
                sender.sendMessage("§cUsage: /vaultx admin snapshot rollback <snapshotId> [player]");
                return true;
            }
            String snapId = args[3];
            if (args.length >= 5) {
                String targetPlayer = args[4];
                OfflinePlayer target = parent.resolvePlayerFast(targetPlayer);
                if (target == null) {
                    sender.sendMessage("§cPlayer not found.");
                    return true;
                }
                sender.sendMessage("§eRolling back player " + targetPlayer + " to snapshot " + snapId + "...");
                snapshotAPI.restorePlayerSnapshotAsync(target.getUniqueId(), snapId).thenAccept(success -> {
                    if (success) {
                        sender.sendMessage("§a✔ Successfully restored player " + targetPlayer + " to snapshot "
                                + snapId + "!");
                    } else {
                        sender.sendMessage("§cFailed to restore player snapshot. Snapshot ID or player data not found.");
                    }
                });
            } else {
                sender.sendMessage("§eRolling back ENTIRE SERVER economy to snapshot " + snapId + "...");
                snapshotAPI.restoreServerSnapshotAsync(snapId).thenAccept(success -> {
                    if (success) {
                        sender.sendMessage("§a✔ SERVER ECONOMY ROLLBACK COMPLETE! All account balances restored to snapshot "
                                + snapId + "!");
                    } else {
                        sender.sendMessage("§cFailed to restore server snapshot. Invalid Snapshot ID.");
                    }
                });
            }
        } else if (sub.equals("delete")) {
            if (args.length < 4) {
                sender.sendMessage("§cUsage: /vaultx admin snapshot delete <snapshotId>");
                return true;
            }
            String snapId = args[3];
            snapshotAPI.deleteSnapshotAsync(snapId).thenAccept(success -> {
                if (success) {
                    sender.sendMessage("§a✔ Deleted snapshot " + snapId);
                } else {
                    sender.sendMessage("§cFailed to delete snapshot.");
                }
            });
        } else {
            sender.sendMessage("§cUsage: /vaultx admin snapshot <create [label] | list | rollback <id> [player] | delete <id>>");
        }
        return true;
    }

    public boolean handleFreeze(CommandSender sender, OfflinePlayer target, String targetName, String[] args) {
        StringBuilder reason = new StringBuilder();
        if (args.length >= 4) {
            for (int i = 3; i < args.length; i++) {
                if (reason.length() > 0) reason.append(" ");
                reason.append(args[i]);
            }
        } else {
            reason.append(parent.getMsg("commands.admin.freeze-reason-manual", "Manual freeze by admin"));
        }
        Vault.getFirewall().freezePlayer(target, reason.toString());
        sender.sendMessage(parent.getMsg("commands.admin.freeze-success", "§a§l✔ §aPlayer §e%player% §ahas been frozen.")
                .replace("%player%", parent.getPlayerNameSafe(target, targetName)));
        return true;
    }

    public boolean handleUnfreeze(CommandSender sender, OfflinePlayer target, String targetName) {
        Vault.getFirewall().unfreezePlayer(target);
        sender.sendMessage(parent.getMsg("commands.admin.unfreeze-success", "§a§l✔ §aPlayer §e%player% §ahas been unfrozen.")
                .replace("%player%", parent.getPlayerNameSafe(target, targetName)));
        return true;
    }

    public boolean handleGiveTakeSet(CommandSender sender, String action, OfflinePlayer target, String targetName, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(parent.getMsg("commands.admin.usage-give-take-set",
                    "§cUsage: /vaultx admin [give/take/set] [player] [amount] [currency]"));
            return true;
        }

        double amount;
        try {
            amount = parent.parseNonNegativeDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(parent.getMsg("general.invalid-amount", "§cInvalid amount. Must be a positive number."));
            return true;
        }

        String currency = args[4];
        Economy econ = parent.getEconomy();
        EconomyResponse response = null;

        if (currency.equalsIgnoreCase("default")) {
            switch (action) {
                case "give":
                    response = econ.depositPlayer(target, amount);
                    break;
                case "take":
                    response = econ.withdrawPlayer(target, amount);
                    break;
                case "set":
                    double current = econ.getBalance(target);
                    if (current < amount) {
                        response = econ.depositPlayer(target, amount - current);
                    } else if (current > amount) {
                        response = econ.withdrawPlayer(target, current - amount);
                    } else {
                        response = new EconomyResponse(0, current, EconomyResponse.ResponseType.SUCCESS, "");
                    }
                    break;
                default:
                    sender.sendMessage(parent.getMsg("commands.admin.invalid-action",
                            "§cUnknown action. Use give, take, set, freeze, or unfreeze."));
                    return true;
            }
        } else if (econ instanceof MultiCurrencyEconomy mcEcon) {
            switch (action) {
                case "give":
                    response = mcEcon.depositCurrencyPlayer(target, currency, amount);
                    break;
                case "take":
                    response = mcEcon.withdrawCurrencyPlayer(target, currency, amount);
                    break;
                case "set":
                    double current = mcEcon.getCurrencyBalance(target, currency);
                    if (current < amount) {
                        response = mcEcon.depositCurrencyPlayer(target, currency, amount - current);
                    } else if (current > amount) {
                        response = mcEcon.withdrawCurrencyPlayer(target, currency, current - amount);
                    } else {
                        response = new EconomyResponse(0, current, EconomyResponse.ResponseType.SUCCESS, "");
                    }
                    break;
                default:
                    sender.sendMessage(parent.getMsg("commands.admin.invalid-action",
                            "§cUnknown action. Use give, take, set, freeze, or unfreeze."));
                    return true;
            }
        } else {
            sender.sendMessage(parent.getMsg("commands.admin.multicurrency-unsupported", "§cMulti-currency is not supported."));
            return true;
        }

        if (response != null && response.transactionSuccess()) {
            sender.sendMessage(parent.getMsg("commands.admin.success-action",
                    "§a§l[VaultX] §aSuccessfully performed §e%action% §aof §f%amount% §7(%currency%) §afor §f%player%")
                    .replace("%action%", action)
                    .replace("%amount%", econ.format(amount))
                    .replace("%currency%", currency)
                    .replace("%player%", parent.getPlayerNameSafe(target, targetName)));
        } else {
            sender.sendMessage(parent.getMsg("commands.admin.failure-action", "§c§l[VaultX] §cTransaction failed: %error%")
                    .replace("%error%", (response != null ? response.errorMessage : "Unknown error")));
        }
        return true;
    }
}
