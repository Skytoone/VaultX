package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.command.subcommands.bank.BankMemberHandler;
import net.milkbowl.vault.command.subcommands.bank.BankTransactionHandler;
import net.milkbowl.vault.command.subcommands.bank.BankViewHandler;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class BankSubCommand implements SubCommand {

    private final VaultXCommand parent;
    private final BankMemberHandler memberHandler;
    private final BankTransactionHandler transactionHandler;
    private final BankViewHandler viewHandler;

    public BankSubCommand(VaultXCommand parent) {
        this.parent = parent;
        this.memberHandler = new BankMemberHandler(parent);
        this.transactionHandler = new BankTransactionHandler(parent);
        this.viewHandler = new BankViewHandler(parent);
    }

    @Override
    public String getName() {
        return "bank";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.command.bank";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(parent.getMsg("general.only-players", "§cOnly players can execute this command."));
            return true;
        }

        Economy econ = parent.getEconomy();
        if (econ == null) {
            sender.sendMessage(parent.getMsg("general.economy-not-found", "§cNo economy provider registered!"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(parent.getMsg("bank.usage",
                    "§cUsage: /vaultx bank [create/delete/bal/deposit/withdraw/list/invite/invites/accept/deny/removemember] ..."));
            return true;
        }

        String sub = args[1].toLowerCase();
        net.milkbowl.vault.redis.LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm == null) {
            sender.sendMessage(parent.getMsg("general.database-error", "§cThe database manager is not initialized."));
            return true;
        }

        if (sub.equals("invites")) {
            return memberHandler.handleInvites(sender, player, fm);
        }

        if (sub.equals("create")) {
            return transactionHandler.handleCreate(sender, player, args, fm);
        }

        if (sub.equals("list")) {
            return viewHandler.handleList(sender, player, fm);
        }

        if (args.length < 3) {
            sender.sendMessage(parent.getMsg("bank.usage",
                    "§cUsage: /vaultx bank [create/delete/bal/deposit/withdraw/list/invite/invites/accept/deny/removemember] ..."));
            return true;
        }
        String bankName = args[2].toLowerCase();

        parent.runAsync(() -> {
            UUID owner = fm.getBankAccountOwner(bankName);
            if (owner == null) {
                parent.runSync(() -> sender.sendMessage(parent.getMsg("bank.not-found", "§cShared bank account '%name%' does not exist.")
                        .replace("%name%", bankName)));
                return;
            }

            String userRole = fm.getBankRole(bankName, player.getUniqueId());
            if (userRole == null) {
                parent.runSync(() -> sender.sendMessage(parent.getMsg("bank.not-member", "§cYou are not a member of the bank account '%name%'.")
                        .replace("%name%", bankName)));
                return;
            }
            if (userRole.startsWith("INVITED_") && !sub.equals("accept") && !sub.equals("deny")) {
                parent.runSync(() -> sender.sendMessage(parent.getMsg("bank.invited-must-accept",
                        "§cYou must first accept the invitation to perform this action.")));
                return;
            }
            parent.runSync(() -> {
                switch (sub) {
                    case "delete":
                        transactionHandler.handleDelete(sender, bankName, userRole, fm);
                        break;
                    case "bal":
                    case "balance":
                        viewHandler.handleBalance(sender, bankName, fm);
                        break;
                    case "deposit":
                        transactionHandler.handleDeposit(sender, player, bankName, userRole, args, fm);
                        break;
                    case "withdraw":
                        transactionHandler.handleWithdraw(sender, player, bankName, userRole, args, fm);
                        break;
                    case "addmember":
                        memberHandler.handleAddMember(sender, player, bankName, userRole, args, fm);
                        break;
                    case "removemember":
                        memberHandler.handleRemoveMember(sender, player, bankName, userRole, args, fm);
                        break;
                    case "invite":
                        memberHandler.handleInvite(sender, player, bankName, userRole, args, fm);
                        break;
                    case "accept":
                        memberHandler.handleAccept(sender, player, bankName, userRole, fm);
                        break;
                    case "deny":
                        memberHandler.handleDeny(sender, player, bankName, userRole, fm);
                        break;
                    default:
                        sender.sendMessage(parent.getMsg("bank.unknown-subcommand", "§cUnknown bank subcommand."));
                        break;
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
