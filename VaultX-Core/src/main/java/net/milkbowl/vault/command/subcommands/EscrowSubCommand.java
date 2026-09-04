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

public class EscrowSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public EscrowSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "escrow";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.command.escrow";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        net.milkbowl.vault.redis.EscrowManager em = Vault.getEscrowManager();
        if (em == null) {
            sender.sendMessage(parent.getMsg("commands.escrow.manager-uninitialized",
                    "§c§l[VaultX] §cEscrow manager is not initialized."));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(parent.getMsg("commands.escrow.usage", "§cUsage: /vaultx escrow [start/release/refund/list] ..."));
            return true;
        }

        String sub = args[1].toLowerCase();
        switch (sub) {
            case "start":
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(parent.getMsg("commands.escrow.only-players-start",
                            "§cOnly players can start escrow transactions."));
                    return true;
                }
                if (args.length < 4) {
                    sender.sendMessage(parent.getMsg("commands.escrow.usage-start",
                            "§cUsage: /vaultx escrow start <receiver> <amount> [currency]"));
                    return true;
                }
                OfflinePlayer receiver = parent.resolvePlayerFast(args[2]);
                if (receiver == null || (!net.milkbowl.vault.util.UUIDCache.hasPlayedBeforeFast(receiver) && !receiver.isOnline())) {
                    sender.sendMessage(parent.getMsg("commands.escrow.player-never-played",
                            "§cPlayer '%player%' has never played before.").replace("%player%", args[2]));
                    return true;
                }
                double amount;
                try {
                    amount = parent.parsePositiveDouble(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(parent.getMsg("general.invalid-amount", "§cInvalid amount. Must be a positive number."));
                    return true;
                }
                String currency = args.length >= 5 ? args[4] : "default";

                sender.sendMessage(parent.getMsg("commands.escrow.initiating", "§eInitiating escrow transaction..."));
                em.startEscrow(player, receiver, amount, currency, 300).thenAccept(res -> {
                    parent.runSync(() -> {
                        if (res.success) {
                            sender.sendMessage(parent.getMsg("commands.escrow.success-started",
                                    "§a§l✔ §aEscrow transaction started! ID: §e%id%").replace("%id%", res.escrowId));
                            sender.sendMessage(parent.getMsg("commands.escrow.success-started-info",
                                    "§7The funds are locked. Use §f/vx escrow release %id% §7once you receive your items/services.")
                                    .replace("%id%", res.escrowId));
                        } else {
                            sender.sendMessage(parent.getMsg("commands.escrow.failed-start", "§c§l❌ §cEscrow failed: %error%")
                                    .replace("%error%", res.message));
                        }
                    });
                });
                break;

            case "release":
                if (args.length < 3) {
                    sender.sendMessage(parent.getMsg("commands.escrow.usage-release", "§cUsage: /vaultx escrow release <id>"));
                    return true;
                }
                String releaseId = args[2];
                sender.sendMessage(parent.getMsg("commands.escrow.releasing", "§eReleasing escrow %id%...").replace("%id%", releaseId));
                em.releaseEscrow(releaseId, sender).thenAccept(res -> {
                    parent.runSync(() -> {
                        if (res.success) {
                            sender.sendMessage(parent.getMsg("commands.escrow.success-released",
                                    "§a§l✔ §aEscrow %id% released successfully!").replace("%id%", releaseId));
                        } else {
                            sender.sendMessage(parent.getMsg("commands.escrow.failed-release",
                                    "§c§l❌ §cFailed to release escrow: %error%").replace("%error%", res.message));
                        }
                    });
                });
                break;

            case "refund":
                if (args.length < 3) {
                    sender.sendMessage(parent.getMsg("commands.escrow.usage-refund", "§cUsage: /vaultx escrow refund <id>"));
                    return true;
                }
                String refundId = args[2];
                sender.sendMessage(parent.getMsg("commands.escrow.refunding", "§eRefunding escrow %id%...").replace("%id%", refundId));
                em.refundEscrow(refundId, sender).thenAccept(res -> {
                    parent.runSync(() -> {
                        if (res.success) {
                            sender.sendMessage(parent.getMsg("commands.escrow.success-refunded",
                                    "§a§l✔ §aEscrow %id% refunded successfully!").replace("%id%", refundId));
                        } else {
                            sender.sendMessage(parent.getMsg("commands.escrow.failed-refund", "§c§l❌ §cFailed to refund escrow: %error%")
                                    .replace("%error%", res.message));
                        }
                    });
                });
                break;

            case "list":
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(parent.getMsg("commands.escrow.only-players-list",
                            "§cOnly players can list their active escrows."));
                    return true;
                }
                sender.sendMessage(parent.getMsg("commands.escrow.fetching", "§eFetching your active escrows..."));
                em.listEscrows(player).thenAccept(list -> {
                    parent.runSync(() -> {
                        if (list.isEmpty()) {
                            sender.sendMessage(parent.getMsg("commands.escrow.empty",
                                    "§e§l[VaultX Escrow] §cYou have no active escrow transactions."));
                            return;
                        }
                        sender.sendMessage(parent.getMsg("commands.escrow.header", "§b§l=== Your Active Escrows ==="));
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        for (net.milkbowl.vault.redis.EscrowManager.EscrowDetails desc : list) {
                            String role = desc.sender.equals(player.getUniqueId()) ? "§a[SENDER]" : "§d[RECEIVER]";
                            String time = sdf.format(new java.util.Date(desc.timeoutAt));
                            Economy econ = parent.getEconomy();
                            String amountStr = (econ != null) ? econ.format(desc.amount) : String.format("%.2f", desc.amount);
                            sender.sendMessage(parent.getMsg("commands.escrow.entry",
                                    "§7- ID: §e%id% §f%role% §7| Montant: §e%amount% §7(%currency%) | Statut: §f%status% §7| Exp: %time%")
                                    .replace("%id%", desc.id)
                                    .replace("%role%", role)
                                    .replace("%amount%", amountStr)
                                    .replace("%currency%", desc.currency)
                                    .replace("%status%", desc.status)
                                    .replace("%time%", time));
                        }
                        sender.sendMessage(parent.getMsg("commands.escrow.footer", "§b§l============================="));
                    });
                });
                break;

            default:
                sender.sendMessage(parent.getMsg("commands.escrow.unknown-subcommand",
                        "§cUnknown escrow sub-command. Use start, release, refund, or list."));
                break;
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
