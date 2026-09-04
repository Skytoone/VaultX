package net.milkbowl.vault.gui.view;

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
import net.milkbowl.vault.util.FoliaScheduler;
import net.milkbowl.vault.util.VaultXGUI;
import net.milkbowl.vault.util.VaultXGUI.GUIStocksHolder;
import net.milkbowl.vault.util.VaultXVisuals;

import static net.milkbowl.vault.gui.view.GUIHelper.*;

public class StocksGUIView {

    private final Plugin plugin;

    public StocksGUIView(Plugin plugin) {
        this.plugin = plugin;
    }

    private ItemStack loadCommodityItem(String key, Material defMat, String defName, double price, double shares, Economy econ) {
        Map<String, String> reps = new HashMap<>();
        reps.put("%price%", econ != null ? econ.format(price) : String.valueOf(price));
        reps.put("%shares%", String.format("%.2f", shares));
        reps.put("%value%", econ != null ? econ.format(price * shares) : String.format("%.2f", price * shares));

        String[] defLore = new String[] {
            "§7Current price: §e" + (econ != null ? econ.format(price) : price),
            "§7Your shares: §f" + String.format("%.2f", shares),
            "§7Value: §a" + (econ != null ? econ.format(price * shares) : String.format("%.2f", price * shares)),
            "",
            "§e▶ Left Click: §fBuy 1 share",
            "§e▶ Right Click: §fSell 1 share",
            "§e▶ Shift + Left: §fBuy 10 shares",
            "§e▶ Shift + Right: §fSell 10 shares"
        };

        return loadItem("stocks.items." + key, defMat, 0, defName, defLore, reps);
    }

    private ItemStack loadBankSharesItem(String bank, double shares, double bal, double sharePrice, Economy econ) {
        Map<String, String> reps = new HashMap<>();
        reps.put("%bank%", bank.toUpperCase());
        reps.put("%balance%", econ != null ? econ.format(bal) : String.valueOf(bal));
        reps.put("%price%", econ != null ? econ.format(sharePrice) : String.valueOf(sharePrice));
        reps.put("%sell_price%", econ != null ? econ.format(sharePrice * 0.95) : String.valueOf(sharePrice * 0.95));
        reps.put("%owned%", String.format("%.2f", shares));
        reps.put("%value%", econ != null ? econ.format(sharePrice * shares) : String.format("%.2f", sharePrice * shares));

        String[] defLore = new String[] {
            "§7Bank balance: §a" + (econ != null ? econ.format(bal) : bal),
            "§7Price of a share (1%): §e" + (econ != null ? econ.format(sharePrice) : sharePrice),
            "§7Your shares: §f" + String.format("%.2f", shares) + " §7/ 100.0",
            "§7Value: §a" + (econ != null ? econ.format(sharePrice * shares) : String.format("%.2f", sharePrice * shares)),
            "",
            "§e▶ Left Click: §fBuy 1 share",
            "§e▶ Right Click: §fSell 1 share",
            "§e▶ Shift + Left: §fBuy 10 shares",
            "§e▶ Shift + Right: §fSell 10 shares"
        };

        return loadItem("stocks.items.bank-shares", CHEST, 0, "§d§lShares: " + bank.toUpperCase(), defLore, reps);
    }

    public void openStocks(Player player) {
        String title = getGuiTitle("stocks", "§d§lStock & Bank Market");
        Inventory inv = Bukkit.createInventory(new GUIStocksHolder(), 36, title);
        LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm == null)
            return;

        FoliaScheduler.runAsync(plugin, () -> {
            double goldPrice = fm.getCommodityPrice("gold");
            double ironPrice = fm.getCommodityPrice("iron");
            double emeraldPrice = fm.getCommodityPrice("emerald");
            double diamondPrice = fm.getCommodityPrice("diamond");

            double goldShares = fm.getPlayerStockShares(player.getUniqueId(), "gold");
            double ironShares = fm.getPlayerStockShares(player.getUniqueId(), "iron");
            double emeraldShares = fm.getPlayerStockShares(player.getUniqueId(), "emerald");
            double diamondShares = fm.getPlayerStockShares(player.getUniqueId(), "diamond");

            List<String> playerBanks = fm.getBanksForPlayer(player.getUniqueId());
            Map<String, Double> bankShares = new HashMap<>();
            Map<String, Double> bankBalances = new HashMap<>();
            Map<String, Double> bankSharePrices = new HashMap<>();

            Economy econ = getEconomy();

            for (String bank : playerBanks) {
                double shares = fm.getPlayerStockShares(player.getUniqueId(), "bank:" + bank.toLowerCase());
                bankShares.put(bank, shares);

                double bal = 0.0;
                if (econ != null) {
                    bal = econ.bankBalance(bank).balance;
                    if (bal == 0.0 && !econ.bankBalance(bank).transactionSuccess()) {
                        bal = fm.getBankBalance(bank);
                    }
                }
                bankBalances.put(bank, bal);
                bankSharePrices.put(bank, Math.max(10.0, bal / 100.0));
            }

            FoliaScheduler.runSync(plugin, () -> {
                inv.setItem(10, loadCommodityItem("gold", GOLD_INGOT, "§e§lGold", goldPrice, goldShares, econ));
                inv.setItem(12, loadCommodityItem("iron", IRON_INGOT, "§f§lIron", ironPrice, ironShares, econ));
                inv.setItem(14, loadCommodityItem("emerald", EMERALD, "§a§lEmerald", emeraldPrice, emeraldShares, econ));
                inv.setItem(16, loadCommodityItem("diamond", DIAMOND, "§b§lDiamond", diamondPrice, diamondShares, econ));

                int slot = 28;
                for (String bank : playerBanks) {
                    if (slot > 34)
                        break;
                    double shares = bankShares.get(bank);
                    double bal = bankBalances.get(bank);
                    double sharePrice = bankSharePrices.get(bank);

                    inv.setItem(slot++, loadBankSharesItem(bank, shares, bal, sharePrice, econ));
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

        double amount = event.isShiftClick() ? 10.0 : 1.0;
        boolean buy = event.isLeftClick();

        if (slot == 10 || slot == 12 || slot == 14 || slot == 16) {
            String commodity = "gold";
            if (slot == 12)
                commodity = "iron";
            else if (slot == 14)
                commodity = "emerald";
            else if (slot == 16)
                commodity = "diamond";

            final String finalComm = commodity;
            final double finalAmt = amount;

            FoliaScheduler.runAsync(plugin, () -> {
                double price = fm.getCommodityPrice(finalComm);
                if (price <= 0.0)
                    return;

                double currentShares = fm.getPlayerStockShares(player.getUniqueId(), finalComm);

                FoliaScheduler.runSync(plugin, () -> {
                    if (buy) {
                        double cost = price * finalAmt;
                        double bal = econ.getBalance(player);
                        if (bal < cost) {
                            player.sendMessage(Vault.getMessage("commands.stocks.insufficient-funds", "§cYou do not have enough funds (Cost: %cost%).")
                                    .replace("%cost%", econ.format(cost)));
                            VaultXVisuals.playFailureSound(player);
                            return;
                        }

                        EconomyResponse wRes = econ.withdrawPlayer(player, cost);
                        if (wRes.transactionSuccess()) {
                            double newShares = currentShares + finalAmt;
                            double newPrice = price * (1.0 + 0.0005 * finalAmt);

                            FoliaScheduler.runAsync(plugin, () -> {
                                fm.updatePlayerStockShares(player.getUniqueId(), finalComm, newShares);
                                fm.updateCommodityPrice(finalComm, newPrice);
                                fm.savePlayerTransaction(player.getUniqueId(), "WITHDRAW_STOCK_BUY", "default", cost,
                                        finalComm.toUpperCase());
                            });

                            player.sendMessage(Vault.getMessage("commands.stocks.buy-success", "§a§l✔ §aSuccessfully purchased %amount% shares of %commodity%!")
                                    .replace("%amount%", String.valueOf(finalAmt))
                                    .replace("%commodity%", finalComm.toUpperCase()));
                            openStocks(player);
                        } else {
                            player.sendMessage(Vault.getMessage("commands.stocks.tx-failed", "&cTransaction failed: %error%")
                                    .replace("%error%", wRes.errorMessage));
                            VaultXVisuals.playFailureSound(player);
                        }
                    } else { // sell
                        if (currentShares < finalAmt) {
                            player.sendMessage(Vault.getMessage("commands.stocks.insufficient-shares", "§cYou do not own enough shares (Available: %available%).")
                                    .replace("%available%", String.valueOf(currentShares)));
                            VaultXVisuals.playFailureSound(player);
                            return;
                        }

                        double payout = price * finalAmt;
                        EconomyResponse dRes = econ.depositPlayer(player, payout);
                        if (dRes.transactionSuccess()) {
                            double newShares = currentShares - finalAmt;
                            double newPrice = Math.max(1.0, price * (1.0 - 0.0005 * finalAmt));

                            FoliaScheduler.runAsync(plugin, () -> {
                                fm.updatePlayerStockShares(player.getUniqueId(), finalComm, newShares);
                                fm.updateCommodityPrice(finalComm, newPrice);
                                fm.savePlayerTransaction(player.getUniqueId(), "DEPOSIT_STOCK_SELL", "default", payout,
                                        finalComm.toUpperCase());
                            });

                            player.sendMessage(Vault.getMessage("commands.stocks.sell-success", "§a§l✔ §aSuccessfully sold %amount% shares of %commodity%!")
                                    .replace("%amount%", String.valueOf(finalAmt))
                                    .replace("%commodity%", finalComm.toUpperCase()));
                            openStocks(player);
                        } else {
                            player.sendMessage(Vault.getMessage("commands.stocks.tx-failed", "&cTransaction failed: %error%")
                                    .replace("%error%", dRes.errorMessage));
                            VaultXVisuals.playFailureSound(player);
                        }
                    }
                });
            });
        } else if (slot >= 28 && slot <= 34) {
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() != Material.CHEST)
                return;

            final double finalAmt = amount;

            FoliaScheduler.runAsync(plugin, () -> {
                List<String> playerBanks = fm.getBanksForPlayer(player.getUniqueId());
                int bankIdx = slot - 28;
                String bankName = null;
                if (bankIdx >= 0 && bankIdx < playerBanks.size()) {
                    bankName = playerBanks.get(bankIdx);
                } else if (clickedItem.hasItemMeta() && clickedItem.getItemMeta().hasDisplayName()) {
                    String display = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName());
                    if (display.startsWith("Shares: ")) {
                        bankName = display.substring(8).toLowerCase();
                    }
                }
                if (bankName == null) return;
                final String finalBank = bankName;

                double dbBankBal = fm.getBankBalance(finalBank);
                double outstandingShares = fm.getOutstandingBankShares(finalBank);
                double currentShares = fm.getPlayerStockShares(player.getUniqueId(), "bank:" + finalBank);

                FoliaScheduler.runSync(plugin, () -> {
                    double bankBal = econ.bankBalance(finalBank).balance;
                    if (bankBal == 0.0 && !econ.bankBalance(finalBank).transactionSuccess()) {
                        bankBal = dbBankBal;
                    }
                    double sharePrice = Math.max(10.0, bankBal / 100.0);

                    if (buy) {
                        double cost = sharePrice * finalAmt;
                        double bal = econ.getBalance(player);
                        if (bal < cost) {
                            player.sendMessage(Vault.getMessage("commands.stocks.insufficient-funds", "§cYou do not have enough funds (Cost: %cost%).")
                                    .replace("%cost%", econ.format(cost)));
                            VaultXVisuals.playFailureSound(player);
                            return;
                        }

                        if (outstandingShares + finalAmt > 100.0) {
                            player.sendMessage(Vault.getMessage("commands.stocks.bank-outstanding-limit",
                                    "§cCannot buy: global limit of 100 bank shares reached."));
                            VaultXVisuals.playFailureSound(player);
                            return;
                        }

                        EconomyResponse wRes = econ.withdrawPlayer(player, cost);
                        if (wRes.transactionSuccess()) {
                            econ.bankDeposit(finalBank, cost);
                            double newBankBal = bankBal + cost;
                            double newShares = currentShares + finalAmt;

                            FoliaScheduler.runAsync(plugin, () -> {
                                fm.saveBankBalance(finalBank, newBankBal);
                                fm.updatePlayerStockShares(player.getUniqueId(), "bank:" + finalBank, newShares);
                                fm.savePlayerTransaction(player.getUniqueId(), "WITHDRAW_BANK_SHARE_BUY", "default", cost,
                                        finalBank.toUpperCase());
                            });

                            player.sendMessage(Vault.getMessage("commands.stocks.bank-buy-success", "§a§l✔ §aSuccessfully purchased %amount% shares of bank %bank%!")
                                    .replace("%amount%", String.valueOf(finalAmt))
                                    .replace("%bank%", finalBank.toUpperCase()));
                            openStocks(player);
                        } else {
                            player.sendMessage(Vault.getMessage("commands.stocks.pay-failed", "&cPayment failed: %error%")
                                    .replace("%error%", wRes.errorMessage));
                            VaultXVisuals.playFailureSound(player);
                        }
                    } else { // sell
                        if (currentShares < finalAmt) {
                            player.sendMessage(Vault.getMessage("commands.stocks.insufficient-shares", "§cYou do not own enough shares (Available: %available%).")
                                    .replace("%available%", String.valueOf(currentShares)));
                            VaultXVisuals.playFailureSound(player);
                            return;
                        }

                        double payout = sharePrice * finalAmt;
                        if (bankBal < payout) {
                            player.sendMessage(Vault.getMessage("commands.stocks.bank-insufficient-liquidity",
                                    "§cThe bank does not have enough liquidity to buy back your shares."));
                            VaultXVisuals.playFailureSound(player);
                            return;
                        }

                        EconomyResponse wRes = econ.bankWithdraw(finalBank, payout);
                        if (wRes.transactionSuccess()) {
                            econ.depositPlayer(player, payout);
                            double newBankBal = Math.max(0.0, bankBal - payout);
                            double newShares = currentShares - finalAmt;

                            FoliaScheduler.runAsync(plugin, () -> {
                                fm.saveBankBalance(finalBank, newBankBal);
                                fm.updatePlayerStockShares(player.getUniqueId(), "bank:" + finalBank, newShares);
                                fm.savePlayerTransaction(player.getUniqueId(), "DEPOSIT_BANK_SHARE_SELL", "default", payout,
                                        finalBank.toUpperCase());
                            });

                            player.sendMessage(Vault.getMessage("commands.stocks.bank-sell-success", "§a§l✔ §aSuccessfully sold %amount% shares of bank %bank%!")
                                    .replace("%amount%", String.valueOf(finalAmt))
                                    .replace("%bank%", finalBank.toUpperCase()));
                            openStocks(player);
                        } else {
                            player.sendMessage(Vault.getMessage("commands.stocks.buyback-failed", "&cBuyback failed: %error%")
                                    .replace("%error%", wRes.errorMessage));
                            VaultXVisuals.playFailureSound(player);
                        }
                    }
                });
            });
        }
    }
}
