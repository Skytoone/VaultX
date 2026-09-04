package net.milkbowl.vault.economy;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.redis.LocalFailoverManager.BankShareholderRecord;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.milkbowl.vault.economy.events.VaultStockPriceChangeEvent;
import java.util.concurrent.CompletableFuture;
import java.util.*;

public class StockExchangeManager implements VaultStockAPI {

    private final Plugin plugin;
    private final LocalFailoverManager failoverManager;
    private final MarketPriceEngine priceEngine;

    private org.bukkit.scheduler.BukkitTask fluctuationTask;
    private org.bukkit.scheduler.BukkitTask dividendsTask;

    public StockExchangeManager(Plugin plugin) {
        this.plugin = plugin;
        this.failoverManager = Vault.getFailoverManager();
        this.priceEngine = new MarketPriceEngine(plugin, failoverManager);
        this.priceEngine.initPrices();
        startScheduler();
    }

    public void close() {
        if (fluctuationTask != null) {
            try {
                fluctuationTask.cancel();
            } catch (Exception ignored) {}
            fluctuationTask = null;
        }
        if (dividendsTask != null) {
            try {
                dividendsTask.cancel();
            } catch (Exception ignored) {}
            dividendsTask = null;
        }
    }

    private Economy getEconomy() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    private void startScheduler() {
        // Price fluctuations every 5 minutes (6000 ticks)
        long stockInterval = plugin.getConfig().getLong("stocks.fluctuation-interval-minutes", 5L) * 60L * 20L;
        fluctuationTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, priceEngine::fluctuatePrices, stockInterval, stockInterval);

        // Dividend payouts
        long dividendInterval = plugin.getConfig().getLong("stocks.dividends.payout-interval-minutes", 60L) * 60L * 20L;
        dividendsTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, this::payDividends, dividendInterval,
                dividendInterval);
    }

    public void payDividends() {
        if (failoverManager == null)
            return;
        Economy econ = getEconomy();
        if (econ == null)
            return;

        List<BankShareholderRecord> holders = failoverManager.getBankShareholders();
        if (holders.isEmpty())
            return;

        // Group by bank name to calculate totals and balance check
        Map<String, List<BankShareholderRecord>> grouped = new HashMap<>();
        for (BankShareholderRecord h : holders) {
            grouped.computeIfAbsent(h.bankName, k -> new ArrayList<>()).add(h);
        }

        // Pre-fetch bank balances from the database asynchronously (we are currently in the async timer thread)
        Map<String, Double> dbBankBalances = new HashMap<>();
        for (String bankName : grouped.keySet()) {
            dbBankBalances.put(bankName, failoverManager.getBankBalance(bankName));
        }

        net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
            for (Map.Entry<String, List<BankShareholderRecord>> entry : grouped.entrySet()) {
                String bankName = entry.getKey();
                double bankBalance = econ.bankBalance(bankName).balance;
                if (bankBalance == 0 && !econ.bankBalance(bankName).transactionSuccess()) {
                    bankBalance = dbBankBalances.getOrDefault(bankName, 0.0);
                }

                if (bankBalance <= 0)
                    continue;

                // Dividend pool % of bank balance
                double poolPercent = plugin.getConfig().getDouble("stocks.dividends.pool-percent", 1.0) / 100.0;
                double totalDividends = bankBalance * poolPercent;
                if (totalDividends <= 0.0)
                    continue;

                double paidFromBank = 0.0;
                for (BankShareholderRecord record : entry.getValue()) {
                    double dividend = totalDividends * (record.shares / 100.0);
                    if (dividend <= 0.0)
                        continue;

                    // Pay to player
                    OfflinePlayer op = Bukkit.getOfflinePlayer(record.uuid);
                    Player p = Bukkit.getPlayer(record.uuid);
                    net.milkbowl.vault.util.FoliaScheduler.runEntitySync(plugin, p != null ? p : op.getPlayer(), () -> {
                        EconomyResponse depRes = econ.depositPlayer(op, dividend);
                        if (depRes.transactionSuccess()) {
                            final double finalDividend = dividend;
                            final String finalBank = bankName;
                            if (failoverManager != null) {
                                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                                    failoverManager.savePlayerTransaction(record.uuid, "DEPOSIT_DIVIDEND", "default", finalDividend,
                                            "Bank:" + finalBank);
                                });
                            }

                            if (p != null && p.isOnline()) {
                                p.sendMessage(Vault.getMessage("dividends.received",
                                        "§a§l[Dividends] §aYou received §e%amount% §aof dividends for your shares in bank §e%bank%§a.")
                                        .replace("%amount%", econ.format(dividend))
                                        .replace("%bank%", bankName));
                            }
                        }
                    });
                    paidFromBank += dividend;
                }

                if (paidFromBank > 0.0) {
                    econ.bankWithdraw(bankName, paidFromBank);
                }
            }
        });
    }

    private final java.util.Set<String> activeShareTransactions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public double getCommodityPrice(String commodity) {
        if (failoverManager == null || commodity == null) return 0.0;
        return failoverManager.getCommodityPrice(commodity.toLowerCase());
    }

    @Override
    public double getPlayerShares(UUID playerUuid, String commodityOrBank) {
        if (failoverManager == null || playerUuid == null || commodityOrBank == null) return 0.0;
        return failoverManager.getPlayerStockShares(playerUuid, commodityOrBank.toLowerCase());
    }

    @Override
    public CompletableFuture<Boolean> buySharesAsync(Player player, String commodity, double shares) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (player == null || commodity == null || Double.isNaN(shares) || Double.isInfinite(shares) || shares <= 0 || failoverManager == null) {
            future.complete(false);
            return future;
        }
        String comm = commodity.toLowerCase();
        String lockKey = player.getUniqueId() + ":" + comm;
        if (!activeShareTransactions.add(lockKey)) {
            future.complete(false);
            return future;
        }
        future.whenComplete((res, ex) -> activeShareTransactions.remove(lockKey));

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            try {
                double price = failoverManager.getCommodityPrice(comm);
                if (price <= 0.0) {
                    future.complete(false);
                    return;
                }
                double cost = price * shares;
                Economy econ = getEconomy();
                if (econ == null) {
                    future.complete(false);
                    return;
                }
                net.milkbowl.vault.util.FoliaScheduler.runEntitySync(plugin, player, () -> {
                    try {
                        if (econ.getBalance(player) < cost) {
                            future.complete(false);
                            return;
                        }
                        EconomyResponse wRes = econ.withdrawPlayer(player, cost);
                        if (wRes.transactionSuccess()) {
                            net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                                try {
                                    double currentShares = failoverManager.getPlayerStockShares(player.getUniqueId(), comm);
                                    failoverManager.updatePlayerStockShares(player.getUniqueId(), comm, currentShares + shares);
                                    double newPrice = price * (1.0 + 0.0005 * shares);
                                    failoverManager.updateCommodityPrice(comm, newPrice);
                                    net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                                        Bukkit.getPluginManager().callEvent(new VaultStockPriceChangeEvent(comm, price, newPrice));
                                    });
                                    failoverManager.savePlayerTransaction(player.getUniqueId(), "WITHDRAW_STOCK_BUY", "default", cost, comm.toUpperCase());
                                    future.complete(true);
                                } catch (Exception e) {
                                    future.completeExceptionally(e);
                                }
                            });
                        } else {
                            future.complete(false);
                        }
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> sellSharesAsync(Player player, String commodity, double shares) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (player == null || commodity == null || Double.isNaN(shares) || Double.isInfinite(shares) || shares <= 0 || failoverManager == null) {
            future.complete(false);
            return future;
        }
        String comm = commodity.toLowerCase();
        String lockKey = player.getUniqueId() + ":" + comm;
        if (!activeShareTransactions.add(lockKey)) {
            future.complete(false);
            return future;
        }
        future.whenComplete((res, ex) -> activeShareTransactions.remove(lockKey));

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            try {
                double price = failoverManager.getCommodityPrice(comm);
                double currentShares = failoverManager.getPlayerStockShares(player.getUniqueId(), comm);
                if (price <= 0.0 || currentShares < shares) {
                    future.complete(false);
                    return;
                }
                double payout = price * shares;
                Economy econ = getEconomy();
                if (econ == null) {
                    future.complete(false);
                    return;
                }
                net.milkbowl.vault.util.FoliaScheduler.runEntitySync(plugin, player, () -> {
                    try {
                        EconomyResponse dRes = econ.depositPlayer(player, payout);
                        if (dRes.transactionSuccess()) {
                            net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                                try {
                                    failoverManager.updatePlayerStockShares(player.getUniqueId(), comm, currentShares - shares);
                                    double minPrice = MarketPriceEngine.MIN_PRICES.getOrDefault(comm, 1.0);
                                    double newPrice = Math.max(minPrice, price * (1.0 - 0.0005 * shares));
                                    failoverManager.updateCommodityPrice(comm, newPrice);
                                    net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                                        Bukkit.getPluginManager().callEvent(new VaultStockPriceChangeEvent(comm, price, newPrice));
                                    });
                                    failoverManager.savePlayerTransaction(player.getUniqueId(), "DEPOSIT_STOCK_SELL", "default", payout, comm.toUpperCase());
                                    future.complete(true);
                                } catch (Exception e) {
                                    future.completeExceptionally(e);
                                }
                            });
                        } else {
                            future.complete(false);
                        }
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }
}
