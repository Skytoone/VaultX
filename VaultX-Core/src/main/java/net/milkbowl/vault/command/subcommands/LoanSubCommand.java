package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.redis.LocalFailoverManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Date;
import java.util.List;

public class LoanSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public LoanSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "loan";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.command.loan";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(parent.getMsg("commands.loan.only-players", "§cOnly players can use the loan system."));
            return true;
        }

        Economy econ = parent.getEconomy();
        if (econ == null) {
            sender.sendMessage(parent.getMsg("general.economy-not-found", "§cNo economy provider registered!"));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(parent.getMsg("commands.loan.usage", "§cUsage: /vaultx loan [apply/list/info]"));
            return true;
        }

        String sub = args[1].toLowerCase();
        LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm == null) {
            player.sendMessage(parent.getMsg("commands.loan.database-error", "§cDatabase error."));
            return true;
        }

        if (sub.equals("apply")) {
            if (args.length < 4) {
                player.sendMessage(parent.getMsg("commands.loan.apply-usage", "§cUsage: /vaultx loan apply <bank_name> <amount>"));
                return true;
            }
            String bankName = args[2].toLowerCase();
            double amount;
            try {
                amount = parent.parsePositiveDouble(args[3]);
            } catch (NumberFormatException e) {
                player.sendMessage(parent.getMsg("commands.loan.invalid-amount", "§cInvalid amount. It must be a positive number."));
                return true;
            }

            net.milkbowl.vault.economy.LoanManager lm = Vault.getLoanManager();
            if (lm != null) {
                lm.applyForLoan(player, bankName, amount);
            } else {
                player.sendMessage(parent.getMsg("commands.loan.manager-uninitialized", "§cThe loan manager is not ready."));
            }
        } else if (sub.equals("list")) {
            parent.runAsync(() -> {
                List<LocalFailoverManager.LoanRecord> loans = fm.getLoansForPlayer(player.getUniqueId());
                double debt = fm.getPlayerDebt(player.getUniqueId());

                parent.runSync(() -> {
                    if (loans.isEmpty() && debt <= 0.0) {
                        player.sendMessage(parent.getMsg("commands.loan.empty-loans",
                                "§cYou have no active loans or pending salary garnishments."));
                        return;
                    }

                    player.sendMessage(parent.getMsg("commands.loan.list-header", "§b§l=== Your VaultX Loans & Debts ==="));
                    for (LocalFailoverManager.LoanRecord lr : loans) {
                        String statusColor = lr.status.equalsIgnoreCase("ACTIVE") ? "§a" : "§7";
                        player.sendMessage(parent.getMsg("commands.loan.list-entry",
                                "  §7- ID: §e%id% §7| Bank: §f%bank% §7| Remaining: %color%%remaining% §7| Status: %status%")
                                .replace("%id%", lr.id)
                                .replace("%bank%", lr.bankName.toUpperCase())
                                .replace("%color%", statusColor)
                                .replace("%remaining%", econ.format(lr.remaining))
                                .replace("%status%", lr.status));
                    }
                    if (debt > 0.0) {
                        player.sendMessage(parent.getMsg("commands.loan.list-debt", "  §c- Pending salary garnishments: §e%debt%")
                                .replace("%debt%", econ.format(debt)));
                    }
                    player.sendMessage(parent.getMsg("commands.loan.list-footer", "§b§l======================================"));
                });
            });
        } else if (sub.equals("info")) {
            if (args.length < 3) {
                player.sendMessage(parent.getMsg("commands.loan.info-usage", "§cUsage: /vaultx loan info <id>"));
                return true;
            }
            String loanId = args[2];
            parent.runAsync(() -> {
                List<LocalFailoverManager.LoanRecord> loans = fm.getLoansForPlayer(player.getUniqueId());
                LocalFailoverManager.LoanRecord record = null;
                for (LocalFailoverManager.LoanRecord lr : loans) {
                    if (lr.id.equalsIgnoreCase(loanId)) {
                        record = lr;
                        break;
                    }
                }

                final LocalFailoverManager.LoanRecord finalRec = record;
                parent.runSync(() -> {
                    if (finalRec == null) {
                        player.sendMessage(parent.getMsg("commands.loan.loan-not-found", "§cLoan not found."));
                        return;
                    }

                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm");
                    player.sendMessage(parent.getMsg("commands.loan.info-header", "§b§l=== Loan Details for %id% ===")
                            .replace("%id%", finalRec.id));
                    player.sendMessage(parent.getMsg("commands.loan.info-bank", "  §fLending Bank: §e%bank%")
                            .replace("%bank%", finalRec.bankName.toUpperCase()));
                    player.sendMessage(parent.getMsg("commands.loan.info-principal", "  §fPrincipal Amount: §e%principal%")
                            .replace("%principal%", econ.format(finalRec.principal)));
                    player.sendMessage(parent.getMsg("commands.loan.info-remaining", "  §fRemaining to Repay: §e%remaining%")
                            .replace("%remaining%", econ.format(finalRec.remaining)));
                    player.sendMessage(parent.getMsg("commands.loan.info-interest", "  §fInterest Rate: §e%rate%%")
                            .replace("%rate%", String.valueOf(finalRec.interestRate * 100.0)));
                    player.sendMessage(parent.getMsg("commands.loan.info-next", "  §fNext Due Date: §e%next%")
                            .replace("%next%", finalRec.status.equalsIgnoreCase("ACTIVE") ? sdf.format(new Date(finalRec.nextBilling)) : "Expired/Paid"));
                    player.sendMessage(parent.getMsg("commands.loan.info-status", "  §fStatus: §e%status%")
                            .replace("%status%", finalRec.status));
                    player.sendMessage(parent.getMsg("commands.loan.info-footer", "§b§l======================================="));
                });
            });
        } else {
            player.sendMessage(parent.getMsg("commands.loan.unknown-subcommand", "§cUnknown subcommand. Choices: apply, list, info"));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
