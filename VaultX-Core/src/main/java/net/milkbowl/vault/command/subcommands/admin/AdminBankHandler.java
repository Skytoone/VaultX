package net.milkbowl.vault.command.subcommands.admin;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

public class AdminBankHandler {

    private final VaultXCommand parent;

    public AdminBankHandler(VaultXCommand parent) {
        this.parent = parent;
    }

    public boolean handleTreasury(CommandSender sender) {
        Economy econ = parent.getEconomy();
        net.milkbowl.vault.economy.CentralBankManager cb = Vault.getCentralBankManager();
        String treasuryAccount = cb != null ? cb.getTreasuryAccount() : "tresor_public";
        double balance = econ.bankBalance(treasuryAccount).balance;
        if (balance == 0 && !econ.bankBalance(treasuryAccount).transactionSuccess()) {
            balance = Vault.getFailoverManager().getBankBalance(treasuryAccount);
        }
        double paydayTax = Vault.getDynamicTaxPercent("payday",
                parent.getPlugin().getConfig().getDouble("paydays.tax.percent", 10.0));
        double payTax = cb != null ? cb.getPayTaxPercent() : 2.0;
        double exchangeTax = cb != null ? cb.getExchangeTaxPercent() : 1.0;

        sender.sendMessage(parent.getMsg("commands.admin.treasury.header", "&b&l=== VaultX Public Treasury ==="));
        sender.sendMessage(parent.getMsg("commands.admin.treasury.balance", "&fState Balance: &e%amount%")
                .replace("%amount%", econ.format(balance)));
        sender.sendMessage(parent.getMsg("commands.admin.treasury.account", "&fTreasury Account: &e%account%")
                .replace("%account%", treasuryAccount));
        sender.sendMessage(parent.getMsg("commands.admin.treasury.taxes-header", "&b-- Current Taxes --"));
        sender.sendMessage(parent.getMsg("commands.admin.treasury.taxes-payday", "  &fSalary Tax: &e%percent%%")
                .replace("%percent%", String.valueOf(paydayTax)));
        sender.sendMessage(parent.getMsg("commands.admin.treasury.taxes-pay", "  &fPayment Tax: &e%percent%%")
                .replace("%percent%", String.valueOf(payTax)));
        sender.sendMessage(parent.getMsg("commands.admin.treasury.taxes-exchange", "  &fExchange Tax (Forex): &e%percent%%")
                .replace("%percent%", String.valueOf(exchangeTax)));
        sender.sendMessage(parent.getMsg("commands.admin.treasury.footer", "&b&l======================================="));
        return true;
    }

    public boolean handleSubvention(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(parent.getMsg("commands.admin.subvention.usage",
                    "&cUsage: /vaultx admin subvention <bank_name> <amount>"));
            return true;
        }
        String bName = args[2].toLowerCase();
        double amount;
        try {
            amount = parent.parsePositiveDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(parent.getMsg("commands.admin.subvention.invalid-amount",
                    "&cInvalid amount. It must be a positive number."));
            return true;
        }

        Economy econ = parent.getEconomy();
        net.milkbowl.vault.redis.LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm.getBankAccountOwner(bName) == null) {
            sender.sendMessage(parent.getMsg("commands.admin.subvention.bank-not-found",
                    "&cShared bank account '%bank%' does not exist.").replace("%bank%", bName));
            return true;
        }

        net.milkbowl.vault.economy.CentralBankManager cb = Vault.getCentralBankManager();
        String treasuryAccount = cb != null ? cb.getTreasuryAccount() : "tresor_public";
        double treasuryBalance = econ.bankBalance(treasuryAccount).balance;
        if (treasuryBalance == 0 && !econ.bankBalance(treasuryAccount).transactionSuccess()) {
            treasuryBalance = fm.getBankBalance(treasuryAccount);
        }

        if (treasuryBalance < amount) {
            sender.sendMessage(parent.getMsg("commands.admin.subvention.insufficient-funds",
                    "&cThe Public Treasury does not have enough funds (%amount% available).")
                    .replace("%amount%", econ.format(treasuryBalance)));
            return true;
        }

        econ.bankWithdraw(treasuryAccount, amount);
        econ.bankDeposit(bName, amount);

        double currentTreasury = fm.getBankBalance(treasuryAccount);
        fm.saveBankBalance(treasuryAccount, Math.max(0, currentTreasury - amount));
        double currentBank = fm.getBankBalance(bName);
        fm.saveBankBalance(bName, currentBank + amount);

        sender.sendMessage(parent.getMsg("commands.admin.subvention.success",
                "&a&l✔ &aSubvention of &e%amount% &agranted to bank &e%bank%&a.")
                .replace("%amount%", econ.format(amount))
                .replace("%bank%", bName));
        return true;
    }

    public boolean handleBank(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(parent.getMsg("commands.admin.bank-usage",
                    "§cUsage: /vaultx admin bank [delete/addmember/removemember] <nom> ..."));
            return true;
        }
        String bankSub = args[2].toLowerCase();
        String bName = args[3].toLowerCase();
        Economy econ = parent.getEconomy();

        if (bankSub.equals("delete")) {
            econ.deleteBank(bName);
            Vault.getFailoverManager().deleteBankAccount(bName);
            sender.sendMessage(parent.getMsg("commands.admin.bank-delete-success",
                    "§a§l✔ §aShared bank account §e%name% §ahas been deleted by the administration.")
                    .replace("%name%", bName));
            return true;
        }

        if (bankSub.equals("addmember")) {
            if (args.length < 6) {
                sender.sendMessage(parent.getMsg("commands.admin.bank-addmember-usage",
                        "§cUsage: /vaultx admin bank addmember <name> <player> <role>"));
                return true;
            }
            OfflinePlayer target = parent.resolvePlayerFast(args[4]);
            if (target == null) {
                sender.sendMessage(parent.getMsg("general.player-not-found", "§cPlayer not found."));
                return true;
            }
            String role = args[5].toUpperCase();
            if (!role.equals("OWNER") && !role.equals("MANAGER") && !role.equals("MEMBER")
                    && !role.equals("VIEWER")) {
                sender.sendMessage(parent.getMsg("commands.admin.bank-role-invalid",
                        "§cInvalid role: OWNER, MANAGER, MEMBER, VIEWER."));
                return true;
            }
            Vault.getFailoverManager().addBankMember(bName, target.getUniqueId(), role);
            sender.sendMessage(parent.getMsg("commands.admin.bank-addmember-success",
                    "§a§l✔ §aPlayer §e%player% §aadded to account §e%name% §awith role §e%role%.")
                    .replace("%player%", parent.getPlayerNameSafe(target, args[4]))
                    .replace("%name%", bName)
                    .replace("%role%", role));
            return true;
        }

        if (bankSub.equals("removemember")) {
            if (args.length < 5) {
                sender.sendMessage(parent.getMsg("commands.admin.bank-removemember-usage",
                        "§cUsage: /vaultx admin bank removemember <name> <player>"));
                return true;
            }
            OfflinePlayer target = parent.resolvePlayerFast(args[4]);
            if (target == null) {
                sender.sendMessage(parent.getMsg("general.player-not-found", "§cPlayer not found."));
                return true;
            }
            Vault.getFailoverManager().removeBankMember(bName, target.getUniqueId());
            sender.sendMessage(parent.getMsg("commands.admin.bank-removemember-success",
                    "§a§l✔ §aPlayer §e%player% §aremoved from account §e%name% §aby the administration.")
                    .replace("%player%", parent.getPlayerNameSafe(target, args[4]))
                    .replace("%name%", bName));
            return true;
        }

        sender.sendMessage(parent.getMsg("commands.admin.bank-action-unknown",
                "§cUnknown admin bank action. Choices: delete, addmember, removemember."));
        return true;
    }
}
