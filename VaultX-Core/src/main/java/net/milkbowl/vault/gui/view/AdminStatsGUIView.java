package net.milkbowl.vault.gui.view;

import java.util.*;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.CentralBankManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.redis.LocalFailoverManager.LocalCurrencyStats;
import net.milkbowl.vault.redis.VaultRedisManager;
import net.milkbowl.vault.redis.VaultRedisManager.CurrencyStats;
import net.milkbowl.vault.redis.VaultRedisManager.LeaderboardEntry;
import net.milkbowl.vault.util.FoliaScheduler;
import net.milkbowl.vault.util.VaultXGUI;
import net.milkbowl.vault.util.VaultXGUI.GUIAdminStatsHolder;

import static net.milkbowl.vault.gui.view.GUIHelper.*;

public class AdminStatsGUIView {

    private final Plugin plugin;

    public AdminStatsGUIView(Plugin plugin) {
        this.plugin = plugin;
    }

    public void openAdminStats(Player player) {
        String title = getGuiTitle("admin_stats", "§b§lMacroeconomic Stats");
        Inventory inv = Bukkit.createInventory(new GUIAdminStatsHolder(), 36, title);

        FoliaScheduler.runAsync(plugin, () -> {
            LocalFailoverManager fm = Vault.getFailoverManager();
            Economy econ = getEconomy();
            if (fm == null || econ == null) return;

            List<String> currenciesList = new ArrayList<>();
            currenciesList.add("default");
            if (econ instanceof MultiCurrencyEconomy) {
                for (String curr : ((MultiCurrencyEconomy) econ).getSupportedCurrencies()) {
                    String normalized = curr.toLowerCase();
                    if (!normalized.equals("default") && !currenciesList.contains(normalized)) {
                        currenciesList.add(normalized);
                    }
                }
            }

            Map<String, LocalCurrencyStats> currencyStats = new HashMap<>();
            VaultRedisManager redis = VaultRedisManager.getInstance();
            boolean redisOnline = redis != null && redis.isOnline();

            for (String curr : currenciesList) {
                if (redisOnline) {
                    CurrencyStats stats = redis.getGlobalCurrencyStats(curr);
                    if (stats != null) {
                        currencyStats.put(curr, new LocalCurrencyStats(stats.totalMoney, stats.accountsCount, stats.averageBalance));
                    } else {
                        currencyStats.put(curr, fm.getLocalCurrencyStats(curr));
                    }
                } else {
                    currencyStats.put(curr, fm.getLocalCurrencyStats(curr));
                }
            }

            CentralBankManager cb = Vault.getCentralBankManager();
            final String treasuryAccount = cb != null ? cb.getTreasuryAccount() : "tresor_public";
            double cbBalanceVal = econ.bankBalance(treasuryAccount).balance;
            if (cbBalanceVal == 0 && !econ.bankBalance(treasuryAccount).transactionSuccess()) {
                cbBalanceVal = fm.getBankBalance(treasuryAccount);
            }
            final double cbBalance = cbBalanceVal;

            final double bankLoansDebt = fm.getTotalBankLoansDebt();
            final double playerDebts = fm.getTotalPlayerDebts();
            final double totalDebt = bankLoansDebt + playerDebts;

            final List<LeaderboardEntry> leaderboard;
            if (redisOnline) {
                leaderboard = redis.getLeaderboard("default");
            } else {
                leaderboard = fm.getLocalLeaderboard("default", 10);
            }

            FoliaScheduler.runSync(plugin, () -> {
                int currSlot = 10;
                for (String curr : currenciesList) {
                    if (currSlot > 12) break;
                    LocalCurrencyStats stats = currencyStats.get(curr);
                    double total = stats != null ? stats.totalMoney : 0.0;
                    long accs = stats != null ? stats.accountsCount : 0;
                    double avg = stats != null ? stats.averageBalance : 0.0;

                    String formatTotal = curr.equals("default") ? econ.format(total) : String.format("%.2f %s", total, curr.toUpperCase());
                    String formatAvg = curr.equals("default") ? econ.format(avg) : String.format("%.2f %s", avg, curr.toUpperCase());

                    Material mat = GOLD_INGOT;
                    String displayName = "§e§l" + curr.substring(0, 1).toUpperCase() + curr.substring(1).toLowerCase() + " Supply";
                    if (curr.equals("gems")) mat = EMERALD;
                    else if (curr.equals("tokens")) mat = DIAMOND;

                    String[] lore = new String[] {
                        "§fGlobal Supply: §a" + formatTotal,
                        "§fTotal Accounts: §e" + accs,
                        "§fAverage Balance: §b" + formatAvg
                    };

                    inv.setItem(currSlot, createGuiItem(mat, 1, (short) 0, displayName, lore));
                    currSlot++;
                }

                String formattedCbBal = econ.format(cbBalance);
                Map<String, String> cbReps = new HashMap<>();
                cbReps.put("%treasury%", treasuryAccount);
                cbReps.put("%balance%", formattedCbBal);
                inv.setItem(getSlot("admin_stats.items.central-bank", 14), loadItem("admin_stats.items.central-bank", GOLD_BLOCK, 0, "§6§lCentral Bank Reserve", new String[] {
                    "§fTreasury Account: §7" + treasuryAccount,
                    "§fReserve Balance: §e" + formattedCbBal
                }, cbReps));

                Map<String, String> debtReps = new HashMap<>();
                debtReps.put("%loans_debt%", econ.format(bankLoansDebt));
                debtReps.put("%player_debts%", econ.format(playerDebts));
                debtReps.put("%total_debt%", econ.format(totalDebt));
                inv.setItem(getSlot("admin_stats.items.debts", 15), loadItem("admin_stats.items.debts", REDSTONE, 0, "§c§lMacroeconomic Debts", new String[] {
                    "§fActive Bank Loans: §7" + econ.format(bankLoansDebt),
                    "§fGarnishment Debts: §7" + econ.format(playerDebts),
                    "§c§lTotal Debts: §e" + econ.format(totalDebt)
                }, debtReps));

                List<String> richLore = new ArrayList<>();
                richLore.add("§7Top 10 richest players (Default):");
                int rank = 1;
                for (LeaderboardEntry entry : leaderboard) {
                    if (rank > 10) break;
                    String entryBal = econ.format(entry.balance);
                    richLore.add(String.format("§e#%d  §f%s  §8»  §a%s", rank, entry.name, entryBal));
                    rank++;
                }
                if (leaderboard.isEmpty()) {
                    richLore.add("§cNo data available");
                }
                inv.setItem(getSlot("admin_stats.items.leaderboard", 16), loadItem("admin_stats.items.leaderboard", Material.BOOK, 0, "§d§lWealth Leaderboard", richLore.toArray(new String[0]), null));

                inv.setItem(getSlot("back-item", 31), loadItem("back-item", BARRIER, 0, "§cBack", new String[]{"§7Return to the dashboard"}, null));

                fillBorder(inv);
                player.openInventory(inv);
            });
        });
    }

    public void handleClick(Player player, int slot, VaultXGUI gui) {
        if (slot == getSlot("back-item", 31)) {
            gui.openDashboard(player);
        }
    }
}
