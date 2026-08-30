package net.milkbowl.vault.economy;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.redis.LocalFailoverManager.LoanRecord;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.milkbowl.vault.economy.events.VaultLoanStatusChangeEvent;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

public class LoanManager {

    private final Plugin plugin;
    private final LocalFailoverManager failoverManager;

    private org.bukkit.scheduler.BukkitTask billingTask;

    public LoanManager(Plugin plugin) {
        this.plugin = plugin;
        this.failoverManager = Vault.getFailoverManager();
        startScheduler();
    }

    public void close() {
        if (billingTask != null) {
            try {
                billingTask.cancel();
            } catch (Exception ignored) {}
            billingTask = null;
        }
    }

    private Economy getEconomy() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    private void startScheduler() {
        // Run billing check every 1 minute (1200 ticks)
        billingTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, this::processBilling, 1200L, 1200L);
    }

    private void processBilling() {
        if (failoverManager == null)
            return;
        Economy econ = getEconomy();
        if (econ == null)
            return;

        List<LoanRecord> activeLoans = failoverManager.getActiveLoans();
        long now = System.currentTimeMillis();

        List<LoanRecord> dueLoans = new java.util.ArrayList<>();
        for (LoanRecord loan : activeLoans) {
            if (now >= loan.nextBilling) {
                dueLoans.add(loan);
            }
        }

        if (dueLoans.isEmpty()) {
            return;
        }

        // Pre-fetch bank balances and player debts asynchronously
        Map<String, Double> dbBankBalances = new HashMap<>();
        Map<UUID, Double> dbPlayerDebts = new HashMap<>();
        for (LoanRecord loan : dueLoans) {
            dbBankBalances.put(loan.bankName.toLowerCase(), failoverManager.getBankBalance(loan.bankName));
            dbPlayerDebts.put(loan.borrower, failoverManager.getPlayerDebt(loan.borrower));
        }

        net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
            Set<String> updatedBanks = new HashSet<>();
            Set<UUID> updatedPlayers = new HashSet<>();

            for (LoanRecord loan : dueLoans) {
                processSingleLoanBilling(econ, loan, now, dbBankBalances, dbPlayerDebts, updatedBanks, updatedPlayers);
            }

            // Write all final updated bank balances and player debts asynchronously to database
            if (!updatedBanks.isEmpty() || !updatedPlayers.isEmpty()) {
                Map<String, Double> finalBankBalances = new HashMap<>();
                for (String bank : updatedBanks) {
                    finalBankBalances.put(bank, dbBankBalances.get(bank));
                }
                Map<UUID, Double> finalPlayerDebts = new HashMap<>();
                for (UUID playerUuid : updatedPlayers) {
                    finalPlayerDebts.put(playerUuid, dbPlayerDebts.get(playerUuid));
                }

                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                    for (Map.Entry<String, Double> entry : finalBankBalances.entrySet()) {
                        failoverManager.saveBankBalance(entry.getKey(), entry.getValue());
                    }
                    for (Map.Entry<UUID, Double> entry : finalPlayerDebts.entrySet()) {
                        failoverManager.updatePlayerDebt(entry.getKey(), entry.getValue());
                    }
                });
            }
        });
    }

    private void processSingleLoanBilling(Economy econ, LoanRecord loan, long now, 
                                          Map<String, Double> dbBankBalances, Map<UUID, Double> dbPlayerDebts,
                                          Set<String> updatedBanks, Set<UUID> updatedPlayers) {
        // Bill installment
        double installment = Math.min(loan.remaining, (loan.principal * (1.0 + loan.interestRate)) / 10.0);
        OfflinePlayer borrower = Bukkit.getOfflinePlayer(loan.borrower);
        double balance = econ.getBalance(borrower);

        double paidAmount;
        double unpaidAmount = 0.0;

        String bankKey = loan.bankName.toLowerCase();
        double dbBankBal = dbBankBalances.getOrDefault(bankKey, 0.0);
        double dbPlayerDebt = dbPlayerDebts.getOrDefault(loan.borrower, 0.0);

        if (balance >= installment) {
            EconomyResponse wRes = econ.withdrawPlayer(borrower, installment);
            if (wRes.transactionSuccess()) {
                econ.bankDeposit(loan.bankName, installment);
                
                double newBankBal = dbBankBal + installment;
                dbBankBalances.put(bankKey, newBankBal);
                updatedBanks.add(bankKey);

                paidAmount = installment;
                final double finalInstallment = installment;
                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                    failoverManager.savePlayerTransaction(loan.borrower, "WITHDRAW_LOAN_BILL", "default",
                            finalInstallment, loan.bankName.toUpperCase());
                });

                // Notify borrower
                Player p = Bukkit.getPlayer(loan.borrower);
                if (p != null && p.isOnline()) {
                    p.sendMessage(Vault.getMessage("loans.billing-success",
                            "§a§l[Loan] §aAutomatic repayment of §e%amount% §afor your loan at bank §e%bank%§a.")
                            .replace("%amount%", econ.format(installment))
                            .replace("%bank%", loan.bankName.toUpperCase()));
                }
            } else {
                // If withdraw fails, treat it as unpaid
                paidAmount = 0.0;
                unpaidAmount = installment;
            }
        } else {
            // Insufficient funds
            double currentBal = balance;
            if (currentBal > 0.0) {
                econ.withdrawPlayer(borrower, currentBal);
                econ.bankDeposit(loan.bankName, currentBal);
                final double finalCurrentBal = currentBal;
                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                    failoverManager.savePlayerTransaction(loan.borrower, "WITHDRAW_LOAN_BILL", "default",
                            finalCurrentBal, loan.bankName.toUpperCase());
                });
            }

            unpaidAmount = installment - currentBal;
            paidAmount = currentBal;

            // Apply overdue penalty percentage if configured
            double penaltyPercent = plugin.getConfig().getDouble("loans.overdue-penalty-percent", 2.5);
            if (penaltyPercent > 0.0) {
                unpaidAmount *= (1.0 + (penaltyPercent / 100.0));
            }

            // Government advances the payment to the bank, and player owes the treasury
            econ.bankDeposit(loan.bankName, unpaidAmount);
            
            double newBankBal = dbBankBal + installment;
            double newPlayerDebt = dbPlayerDebt + unpaidAmount;

            dbBankBalances.put(bankKey, newBankBal);
            updatedBanks.add(bankKey);
            
            dbPlayerDebts.put(loan.borrower, newPlayerDebt);
            updatedPlayers.add(loan.borrower);

            Player p = Bukkit.getPlayer(loan.borrower);
            if (p != null && p.isOnline()) {
                p.sendMessage(Vault.getMessage("loans.billing-insufficient",
                        "§c§l[Loan] §cInsufficient funds for installment! §e%paid% §cwere repaid. The unpaid balance of §e%unpaid% §cwas added to your salary garnishment debts.")
                        .replace("%paid%", econ.format(paidAmount))
                        .replace("%unpaid%", econ.format(unpaidAmount)));
            }
        }

        // Update loan state
        double newRemaining = Math.max(0.0, loan.remaining - (paidAmount + unpaidAmount));
        String newStatus = newRemaining <= 0.0 ? "PAID" : "ACTIVE";
        if (newRemaining <= 0.0) {
            Bukkit.getPluginManager().callEvent(new VaultLoanStatusChangeEvent(borrower, loan.id, loan.principal, "default", VaultLoanStatusChangeEvent.LoanStatus.REPAID));
        } else if (unpaidAmount > 0.0) {
            Bukkit.getPluginManager().callEvent(new VaultLoanStatusChangeEvent(borrower, loan.id, unpaidAmount, "default", VaultLoanStatusChangeEvent.LoanStatus.OVERDUE));
        }
        long billingIntervalMs = plugin.getConfig().getLong("loans.billing-interval-minutes", 60L) * 60L * 1000L;
        long nextBilling = loan.nextBilling + billingIntervalMs;

        final double finalRemaining = newRemaining;
        final String finalStatus = newStatus;
        final long finalNextBilling = nextBilling;
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            failoverManager.updateLoanRemaining(loan.id, finalRemaining, finalStatus);
            failoverManager.updateLoanBilling(loan.id, finalNextBilling);
        });
    }

    public boolean applyForLoan(Player player, String bankName, double amount) {
        if (failoverManager == null)
            return false;
        Economy econ = getEconomy();
        if (econ == null)
            return false;

        UUID borrowerUuid = player.getUniqueId();
        String userRole = failoverManager.getBankRole(bankName, borrowerUuid);
        if (userRole == null || userRole.startsWith("INVITED_") || userRole.equals("VIEWER")) {
            player.sendMessage(Vault.getMessage("loans.no-bank-permission",
                    "§cYou must be an active member (OWNER, MANAGER, MEMBER) of the bank to apply for a loan."));
            return false;
        }

        double bankBal = econ.bankBalance(bankName).balance;
        if (bankBal == 0.0 && !econ.bankBalance(bankName).transactionSuccess()) {
            bankBal = failoverManager.getBankBalance(bankName);
        }

        if (bankBal < amount) {
            player.sendMessage(Vault.getMessage("loans.bank-insufficient-funds",
                    "§cThe bank does not have enough funds to finance this loan."));
            return false;
        }

        // Limit active loans and total borrowed amount per player
        int maxLoans = plugin.getConfig().getInt("loans.max-loans-per-player", 3);
        double maxBorrow = plugin.getConfig().getDouble("loans.max-borrow-amount", 500000.0);
        List<LoanRecord> currentLoans = failoverManager.getLoansForPlayer(borrowerUuid);
        long activeCount = currentLoans.stream().filter(l -> l.status.equalsIgnoreCase("ACTIVE")).count();
        if (maxLoans > 0 && activeCount >= maxLoans) {
            player.sendMessage(Vault.getMessage("loans.limit-exceeded", "§cYou already have %limit% active loans.")
                    .replace("%limit%", String.valueOf(maxLoans)));
            return false;
        }

        double totalBorrowed = currentLoans.stream().filter(l -> l.status.equalsIgnoreCase("ACTIVE")).mapToDouble(l -> l.principal).sum();
        if (maxBorrow > 0 && (totalBorrowed + amount) > maxBorrow) {
            player.sendMessage(Vault.getMessage("loans.max-borrow-exceeded", "§cYour total loan balance cannot exceed %amount%!")
                    .replace("%amount%", econ.format(maxBorrow)));
            return false;
        }

        // Create loan record (5% interest rate, hourly billing)
        double interest = plugin.getConfig().getDouble("loans.interest-rate-percent", 5.0) / 100.0;
        long billingIntervalMs = plugin.getConfig().getLong("loans.billing-interval-minutes", 60L) * 60L * 1000L;
        String loanId = UUID.randomUUID().toString().substring(0, 8);

        LoanRecord loan = new LoanRecord(
                loanId,
                borrowerUuid,
                bankName,
                amount,
                amount * (1.0 + interest),
                interest,
                System.currentTimeMillis() + billingIntervalMs,
                "ACTIVE");

        // Perform balances adjustment
        EconomyResponse wRes = econ.bankWithdraw(bankName, amount);
        if (wRes.transactionSuccess()) {
            EconomyResponse dRes = econ.depositPlayer(player, amount);
            if (dRes.transactionSuccess()) {
                failoverManager.saveLoan(loan);

                failoverManager.savePlayerTransaction(borrowerUuid, "DEPOSIT_LOAN_DISBURSE", "default", amount,
                        "Bank:" + bankName.toUpperCase());

                Bukkit.getPluginManager().callEvent(new VaultLoanStatusChangeEvent(player, loanId, amount, "default", VaultLoanStatusChangeEvent.LoanStatus.TAKEN));
                player.sendMessage(Vault.getMessage("loans.applied-success",
                        "§a§l✔ §aLoan §e#%id% §aof §e%amount% §agranted! The total amount with interest of §e%total% §awill be repaid in 10 installments.")
                        .replace("%id%", loanId)
                        .replace("%amount%", econ.format(amount))
                        .replace("%total%", econ.format(amount * (1.0 + interest))));
                return true;
            } else {
                // Revert bank withdraw
                econ.bankDeposit(bankName, amount);
                player.sendMessage(Vault.getMessage("loans.disburse-deposit-failed", "&cFailed to deposit loan funds to your account."));
            }
        } else {
            player.sendMessage(Vault.getMessage("loans.disburse-withdraw-failed", "&cFailed to withdraw loan funds from the bank."));
        }

        return false;
    }
}

