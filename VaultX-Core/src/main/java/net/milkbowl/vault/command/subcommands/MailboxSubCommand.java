package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class MailboxSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public MailboxSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "mailbox";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.command.mailbox";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(parent.getMsg("commands.mailbox.only-players",
                    "§cOnly players can manage their mailbox."));
            return true;
        }

        if (args.length == 1) {
            if (Vault.getVaultXGUI() != null) {
                Vault.getVaultXGUI().openMailbox(player);
            } else {
                sender.sendMessage(parent.getMsg("commands.mailbox.usage", "§cUsage: /vaultx mailbox [claim/list]"));
            }
            return true;
        }

        String sub = args[1].toLowerCase();
        if (sub.equals("list")) {
            sender.sendMessage(parent.getMsg("commands.mailbox.fetching", "§eRécupération de vos messages en attente..."));
            parent.runAsync(() -> {
                List<net.milkbowl.vault.redis.LocalFailoverManager.MailRecord> mails = Vault.getMailboxManager().getPendingMail(player.getUniqueId());
                parent.runSync(() -> {
                    if (mails.isEmpty()) {
                        player.sendMessage(parent.getMsg("commands.mailbox.empty-box",
                                "§d§l[Mailbox] §cYou have no pending messages."));
                        return;
                    }
                    player.sendMessage(parent.getMsg("commands.mailbox.list-header", "§d§l=== Your Offline Payments ==="));
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm");
                    for (net.milkbowl.vault.redis.LocalFailoverManager.MailRecord mail : mails) {
                        player.sendMessage(parent.getMsg("commands.mailbox.list-entry",
                                "  §7- §e%amount% §7from §f%sender% §7| Message: §7%msg%")
                                .replace("%amount%", String.format("%.2f", mail.amount) + " " + mail.currency.toUpperCase())
                                .replace("%sender%", mail.senderName)
                                .replace("%msg%", mail.message + " (" + sdf.format(new java.util.Date(mail.timestamp)) + ")"));
                    }
                    player.sendMessage(parent.getMsg("commands.mailbox.chat-info", "§7Type §e/vx mailbox claim §7to claim all."));
                });
            });
        } else if (sub.equals("claim") || sub.equals("claimall")) {
            Vault.getMailboxManager().claimAllMail(player);
        } else {
            sender.sendMessage(parent.getMsg("commands.mailbox.usage", "§cUsage: /vaultx mailbox [claim/claimall/list]"));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
