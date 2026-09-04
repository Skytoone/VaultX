package net.milkbowl.vault.command.subcommands.bank;

import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class BankTransactionHandler {

    private final VaultXCommand parent;

    public BankTransactionHandler(VaultXCommand parent) {
        this.parent = parent;
    }

    public boolean handleCreate(CommandSender sender, Player player, String[] args, net.milkbowl.vault.redis.LocalFailoverManager fm) {
        if (args.length < 3) {
            sender.sendMessage(parent.getMsg("bank.usage",
                    "§cUsage: /vaultx bank [create/delete/bal/deposit/withdraw/list/invite/invites/accept/deny/removemember] ..."));
            return true;
        }
        String bankName = args[2].toLowerCase();
        if (!bankName.matches("^[a-zA-Z0-9_]{3,16}$")) {
            sender.sendMessage(parent.getMsg("bank.invalid-name", "§cBank name must be between 3 and 16 alphanumeric characters."));
            return true;
        }

        Economy econ = parent.getEconomy();
        double creationCost = parent.getPlugin().getConfig().getDouble("banks.creation-cost", 5000.0);
        int maxAccounts = parent.getPlugin().getConfig().getInt("banks.max-accounts-per-player", 3);

        if (creationCost > 0 && econ.getBalance(player) < creationCost) {
            sender.sendMessage(parent.getMsg("bank.creation-insufficient-funds",
                    "§cCreating a bank account costs %cost%. Insufficient funds.")
                    .replace("%cost%", econ.format(creationCost)));
            return true;
        }

        parent.runAsync(() -> {
            if (maxAccounts > 0) {
                List<String> ownedBanks = fm.getBanksForPlayer(player.getUniqueId());
                long ownedCount = ownedBanks.stream()
                        .filter(b -> player.getUniqueId().equals(fm.getBankAccountOwner(b))).count();
                if (ownedCount >= maxAccounts) {
                    parent.runSync(() -> sender.sendMessage(parent.getMsg("bank.max-accounts-exceeded",
                            "§cYou cannot own more than %limit% bank accounts.")
                            .replace("%limit%", String.valueOf(maxAccounts))));
                    return;
                }
            }

            if (fm.getBankAccountOwner(bankName) != null) {
                parent.runSync(() -> sender.sendMessage(parent.getMsg("bank.already-exists", "§cA bank account with that name already exists.")));
                return;
            }

            if (creationCost > 0) {
                parent.runSync(() -> econ.withdrawPlayer(player, creationCost));
            }

            fm.createBankAccount(bankName, player.getUniqueId());
            fm.saveBankBalance(bankName, 0.0);

            parent.runSync(() -> {
                econ.createBank(bankName, player);
                sender.sendMessage(parent.getMsg("bank.create-success",
                        "§a§l✔ §aShared bank account §e%name% §asuccessfully created. You are the Owner (OWNER).")
                        .replace("%name%", bankName));
            });
        });
        return true;
    }

    public void handleDelete(CommandSender sender, String bankName, String userRole, net.milkbowl.vault.redis.LocalFailoverManager fm) {
        if (!userRole.equals("OWNER")) {
            sender.sendMessage(parent.getMsg("bank.no-permission-action", "§cOnly the Owner (OWNER) can delete this bank account."));
            return;
        }
        Economy econ = parent.getEconomy();
        parent.runAsync(() -> {
            fm.deleteBankAccount(bankName);
            parent.runSync(() -> {
                econ.deleteBank(bankName);
                sender.sendMessage(parent.getMsg("bank.delete-success",
                        "§a§l✔ §aShared bank account §e%name% §ahas been deleted.")
                        .replace("%name%", bankName));
            });
        });
    }

    public void handleDeposit(CommandSender sender, Player player, String bankName, String userRole, String[] args, net.milkbowl.vault.redis.LocalFailoverManager fm) {
        if (userRole.equals("VIEWER")) {
            sender.sendMessage(parent.getMsg("bank.no-permission-action", "§cYour role (VIEWER) does not allow you to make deposits."));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(parent.getMsg("bank.usage", "§cUsage: /vaultx bank deposit <name> <amount>"));
            return;
        }
        double amount;
        try {
            amount = parent.parsePositiveDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(parent.getMsg("general.invalid-amount", "§cInvalid amount. It must be a positive number."));
            return;
        }

        Economy econ = parent.getEconomy();
        double playerBal = econ.getBalance(player);
        if (playerBal < amount) {
            sender.sendMessage(parent.getMsg("bank.deposit-no-funds", "§cYou do not have enough funds for this deposit."));
            return;
        }

        EconomyResponse wRes = econ.withdrawPlayer(player, amount);
        if (wRes.transactionSuccess()) {
            EconomyResponse dRes = econ.bankDeposit(bankName, amount);
            if (dRes.transactionSuccess()) {
                sender.sendMessage(parent.getMsg("bank.deposit-success",
                        "§a§l✔ §aDeposited §e%amount% §ainto account §e%name%§a.")
                        .replace("%amount%", econ.format(amount))
                        .replace("%name%", bankName));
                parent.runAsync(() -> {
                    double currentBal = fm.getBankBalance(bankName);
                    fm.saveBankBalance(bankName, currentBal + amount);
                });
            } else {
                econ.depositPlayer(player, amount);
                sender.sendMessage(parent.getMsg("bank.deposit-failed", "§c§l❌ §cDeposit failed: %error%")
                        .replace("%error%", dRes.errorMessage));
            }
        } else {
            sender.sendMessage(parent.getMsg("bank.withdraw-failed", "§c§l❌ §cWithdrawal failed: %error%")
                    .replace("%error%", wRes.errorMessage));
        }
    }

    public void handleWithdraw(CommandSender sender, Player player, String bankName, String userRole, String[] args, net.milkbowl.vault.redis.LocalFailoverManager fm) {
        if (userRole.equals("VIEWER")) {
            sender.sendMessage(parent.getMsg("bank.no-permission-action", "§cYour role (VIEWER) does not allow you to make withdrawals."));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(parent.getMsg("bank.usage", "§cUsage: /vaultx bank withdraw <name> <amount>"));
            return;
        }
        double amount;
        try {
            amount = parent.parsePositiveDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(parent.getMsg("general.invalid-amount", "§cInvalid amount. It must be a positive number."));
            return;
        }

        Economy econ = parent.getEconomy();
        parent.runAsync(() -> {
            double bankBal = econ.bankBalance(bankName).balance;
            if (bankBal == 0 && !econ.bankBalance(bankName).transactionSuccess()) {
                bankBal = fm.getBankBalance(bankName);
            }

            final double finalBankBal = bankBal;
            parent.runSync(() -> {
                if (finalBankBal < amount) {
                    sender.sendMessage(parent.getMsg("bank.withdraw-insufficient",
                            "§cInsufficient bank account balance (%amount% available).")
                            .replace("%amount%", econ.format(finalBankBal)));
                    return;
                }

                EconomyResponse wRes = econ.bankWithdraw(bankName, amount);
                if (wRes.transactionSuccess()) {
                    EconomyResponse dRes = econ.depositPlayer(player, amount);
                    if (dRes.transactionSuccess()) {
                        sender.sendMessage(parent.getMsg("bank.withdraw-success",
                                "§a§l✔ §aWithdrew §e%amount% §afrom account §e%name%§a.")
                                .replace("%amount%", econ.format(amount))
                                .replace("%name%", bankName));
                        parent.runAsync(() -> {
                            double currentBal = fm.getBankBalance(bankName);
                            fm.saveBankBalance(bankName, Math.max(0, currentBal - amount));
                        });
                    } else {
                        econ.bankDeposit(bankName, amount);
                        sender.sendMessage(parent.getMsg("bank.withdraw-failed-deposit",
                                "§c§l❌ §cFailed to deposit into your account: %error%")
                                .replace("%error%", dRes.errorMessage));
                    }
                } else {
                    sender.sendMessage(parent.getMsg("bank.withdraw-failed", "§c§l❌ §cBank withdrawal failed: %error%")
                            .replace("%error%", wRes.errorMessage));
                }
            });
        });
    }
}
