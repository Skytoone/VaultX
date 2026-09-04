package net.milkbowl.vault.gui.view;

import java.text.SimpleDateFormat;
import java.util.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.redis.LocalFailoverManager.LoanRecord;
import net.milkbowl.vault.util.FoliaScheduler;
import net.milkbowl.vault.util.VaultXGUI;
import net.milkbowl.vault.util.VaultXGUI.GUILoansHolder;
import net.milkbowl.vault.util.VaultXVisuals;

import static net.milkbowl.vault.gui.view.GUIHelper.*;

public class LoansGUIView {

    private final Plugin plugin;

    public LoansGUIView(Plugin plugin) {
        this.plugin = plugin;
    }

    public void openLoans(Player player) {
        String title = getGuiTitle("loans", "§c§lYour Loans & Credits");
        Inventory inv = Bukkit.createInventory(new GUILoansHolder(), 36, title);
        LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm == null)
            return;

        FoliaScheduler.runAsync(plugin, () -> {
            List<LoanRecord> loans = fm.getLoansForPlayer(player.getUniqueId());
            double debt = fm.getPlayerDebt(player.getUniqueId());
            Economy econ = getEconomy();

            FoliaScheduler.runSync(plugin, () -> {
                int slot = 10;
                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm");

                for (LoanRecord loan : loans) {
                    if (slot > 16 && slot < 19)
                        slot = 19;
                    if (slot > 25)
                        break;

                    boolean active = loan.status.equalsIgnoreCase("ACTIVE");
                    String color = active ? "§a" : "§7";
                    String nextBillingStr = active ? sdf.format(new Date(loan.nextBilling)) : "Expired/Paid";

                    Map<String, String> reps = new HashMap<>();
                    reps.put("%id%", loan.id);
                    reps.put("%bank%", loan.bankName.toUpperCase());
                    reps.put("%principal%", econ != null ? econ.format(loan.principal) : String.valueOf(loan.principal));
                    reps.put("%remaining%", econ != null ? econ.format(loan.remaining) : String.valueOf(loan.remaining));
                    reps.put("%interest%", (loan.interestRate * 100.0) + "%");
                    reps.put("%next%", nextBillingStr);
                    reps.put("%status%", loan.status);

                    String[] defLore = new String[] {
                        "§7Lending bank: §f" + loan.bankName.toUpperCase(),
                        "§7Borrowed amount: §a" + (econ != null ? econ.format(loan.principal) : loan.principal),
                        "§7Remaining to pay (with interest): " + color + (econ != null ? econ.format(loan.remaining) : loan.remaining),
                        "§7Interest rate: §f" + (loan.interestRate * 100.0) + "%",
                        "§7Next installment: §f" + nextBillingStr,
                        "§7Status: §f" + loan.status,
                        "",
                        "§e▶ Left Click: §fRepay 1 installment",
                        "§e▶ Shift + Click: §fRepay in full"
                    };

                    inv.setItem(slot++, loadItem("loans.items.loan-entry", PAPER, 0, "§e§lLoan ID: " + loan.id, defLore, reps));
                }

                if (debt > 0.0) {
                    Map<String, String> reps = new HashMap<>();
                    reps.put("%debt%", econ != null ? econ.format(debt) : String.valueOf(debt));

                    String[] defLore = new String[] {
                        "§7Total pending debt: §e" + (econ != null ? econ.format(debt) : debt),
                        "§7This debt will be automatically deducted",
                        "§7from your next Payday salary."
                    };

                    inv.setItem(22, loadItem("loans.items.salary-garnishment", BARRIER, 0, "§c§lSalary Garnishments", defLore, reps));
                }

                inv.setItem(getSlot("back-item", 31), loadItem("back-item", BARRIER, 0, "§cBack", new String[]{"§7Return to the dashboard"}, null));
                fillBorder(inv);
                player.openInventory(inv);
            });
        });
    }

    public void handleClick(Player player, int slot, InventoryClickEvent event, VaultXGUI gui) {
        if (slot == getSlot("back-item", 31)) {
            gui.openDashboard(player);
            return;
        }

        LocalFailoverManager fm = Vault.getFailoverManager();
        Economy econ = getEconomy();
        if (fm == null || econ == null)
            return;

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() != Material.PAPER)
            return;

        final int clickedSlot = slot;
        String displayLoanId = null;
        if (clickedItem.hasItemMeta() && clickedItem.getItemMeta().hasDisplayName()) {
            String display = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName());
            if (display.startsWith("Loan ID: ")) {
                displayLoanId = display.substring(9).trim();
            }
        }
        final String fallbackLoanId = displayLoanId;

        FoliaScheduler.runAsync(plugin, () -> {
            List<LoanRecord> loans = fm.getLoansForPlayer(player.getUniqueId());
            LoanRecord active = null;

            int listSlot = 10;
            int loanIndex = -1;
            for (int i = 0; i < loans.size(); i++) {
                if (listSlot > 16 && listSlot < 19) listSlot = 19;
                if (listSlot > 25) break;
                if (listSlot == clickedSlot) {
                    loanIndex = i;
                    break;
                }
                listSlot++;
            }

            if (loanIndex >= 0 && loanIndex < loans.size()) {
                LoanRecord lr = loans.get(loanIndex);
                if (lr.status.equalsIgnoreCase("ACTIVE")) {
                    active = lr;
                }
            } else if (fallbackLoanId != null) {
                for (LoanRecord lr : loans) {
                    if (lr.id.equalsIgnoreCase(fallbackLoanId) && lr.status.equalsIgnoreCase("ACTIVE")) {
                        active = lr;
                        break;
                    }
                }
            }

            if (active == null)
                return;

            final LoanRecord finalActive = active;
            final double dbBankBal = fm.getBankBalance(active.bankName);

            FoliaScheduler.runSync(plugin, () -> {
                double repaymentAmount;
                boolean payFull = event.isShiftClick();
                double installment = (finalActive.principal * (1.0 + finalActive.interestRate)) / 10.0;
                if (payFull) {
                    repaymentAmount = finalActive.remaining;
                } else {
                    repaymentAmount = Math.min(finalActive.remaining, installment);
                }

                double bal = econ.getBalance(player);
                if (bal < repaymentAmount) {
                    player.sendMessage(Vault.getMessage("commands.loan.repay-insufficient", "§cYou do not have enough funds for this repayment (Requires: %amount%).")
                            .replace("%amount%", econ.format(repaymentAmount)));
                    VaultXVisuals.playFailureSound(player);
                    return;
                }

                final double finalRepaymentAmount = repaymentAmount;
                EconomyResponse wRes = econ.withdrawPlayer(player, repaymentAmount);
                if (wRes.transactionSuccess()) {
                    econ.bankDeposit(finalActive.bankName, repaymentAmount);

                    double newBankBal = dbBankBal + repaymentAmount;
                    double newRemaining = Math.max(0.0, finalActive.remaining - repaymentAmount);
                    String newStatus = newRemaining <= 0.0 ? "PAID" : "ACTIVE";

                    FoliaScheduler.runAsync(plugin, () -> {
                        fm.saveBankBalance(finalActive.bankName, newBankBal);
                        fm.updateLoanRemaining(finalActive.id, newRemaining, newStatus);
                        fm.savePlayerTransaction(player.getUniqueId(), "WITHDRAW_LOAN_REPAY", "default", finalRepaymentAmount,
                                finalActive.bankName.toUpperCase());
                    });

                    player.sendMessage(Vault.getMessage("commands.loan.repay-success", "§a§l✔ §aRepayment of %amount% successfully made for the loan!")
                            .replace("%amount%", econ.format(repaymentAmount)));
                    openLoans(player);
                } else {
                    player.sendMessage(Vault.getMessage("commands.loan.repay-failed", "§cTransaction failed: %error%")
                            .replace("%error%", wRes.errorMessage));
                    VaultXVisuals.playFailureSound(player);
                }
            });
        });
    }
}
