package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

public class AuditSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public AuditSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "audit";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.admin.audit";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("vault.admin.audit")) {
            sender.sendMessage("§cYou do not have permission!");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /vaultx audit <player> [limit]");
            return true;
        }
        OfflinePlayer target = parent.resolvePlayerFast(args[1]);
        if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
            sender.sendMessage("§cPlayer '" + args[1] + "' not found.");
            return true;
        }
        int limit = 15;
        if (args.length > 2) {
            try {
                limit = Integer.parseInt(args[2]);
                if (limit <= 0) limit = 15;
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid limit number!");
                return true;
            }
        }
        Vault.getAuditAPI().getPlayerTransactionHistoryAsync(target, limit).thenAccept(logs -> {
            parent.runSync(() -> {
                sender.sendMessage("§8§m--------------------------------------------------");
                sender.sendMessage("§6§l🔍 VaultX Audit Log §7(" + target.getName() + ")");
                if (logs.isEmpty()) {
                    sender.sendMessage("§7No transaction logs found.");
                } else {
                    for (var log : logs) {
                        sender.sendMessage("§8[" + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(log.timestamp()))
                                + "] §7" + log.type() + " §a" + log.amount() + " " + log.currency() + " §7("
                                + log.callerPlugin() + ")");
                    }
                }
                sender.sendMessage("§8§m--------------------------------------------------");
            });
        });
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
