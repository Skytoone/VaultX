package net.milkbowl.vault.economy;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.redis.LocalFailoverManager.BankShareholderRecord;
import net.milkbowl.vault.security.TransactionFirewall;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.*;

public class StockExchangeManager {

    private final Plugin plugin;
    private final LocalFailoverManager failoverManager;
    private final Random random = new Random();

    private static final String[] COMMODITIES = { "gold", "iron", "emerald", "diamond" };
    private static final Map<String, Double> DEFAULT_PRICES = new HashMap<>();
    private static final Map<String, Double> MIN_PRICES = new HashMap<>();

    static {
        DEFAULT_PRICES.put("gold", 100.0);
        DEFAULT_PRICES.put("iron", 10.0);
        DEFAULT_PRICES.put("emerald", 250.0);
        DEFAULT_PRICES.put("diamond", 500.0);

        MIN_PRICES.put("gold", 20.0);
        MIN_PRICES.put("iron", 2.0);
        MIN_PRICES.put("emerald", 50.0);
        MIN_PRICES.put("diamond", 100.0);
    }

    private org.bukkit.scheduler.BukkitTask fluctuationTask;
    private org.bukkit.scheduler.BukkitTask dividendsTask;

    public StockExchangeManager(Plugin plugin) {
        this.plugin = plugin;
        this.failoverManager = Vault.getFailoverManager();
        initPrices();
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

    private void initPrices() {
        if (failoverManager == null)
            return;
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            for (String commodity : COMMODITIES) {
                double price = failoverManager.getCommodityPrice(commodity);
                if (price <= 0.0) {
                    failoverManager.updateCommodityPrice(commodity, DEFAULT_PRICES.get(commodity));
                }
            }
        });
    }

    private void startScheduler() {
        // Price fluctuations every 5 minutes (6000 ticks)
        long stockInterval = plugin.getConfig().getLong("stocks.fluctuation-interval-minutes", 5L) * 60L * 20L;
        fluctuationTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, this::fluctuatePrices, stockInterval, stockInterval);

        // Dividend payouts
        long dividendInterval = plugin.getConfig().getLong("stocks.dividends.payout-interval-minutes", 60L) * 60L * 20L;
        dividendsTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, this::payDividends, dividendInterval,
                dividendInterval);
    }

    private void fluctuatePrices() {
        if (failoverManager == null)
            return;

        // 1. Regular fluctuation
        double maxFluctuation = plugin.getConfig().getDouble("stocks.max-fluctuation-percent", 3.0) / 100.0;
        for (String commodity : COMMODITIES) {
            double current = failoverManager.getCommodityPrice(commodity);
            if (current <= 0.0) {
                current = DEFAULT_PRICES.get(commodity);
            }
            double changePercent = (random.nextDouble() - 0.5) * (maxFluctuation * 2.0); // random walk
            double newPrice = Math.max(MIN_PRICES.get(commodity), current * (1.0 + changePercent));
            failoverManager.updateCommodityPrice(commodity, newPrice);
        }

        // 2. Chance of a market news event
        double eventChance = plugin.getConfig().getDouble("stocks.event-chance-percent", 15.0) / 100.0;
        if (random.nextDouble() < eventChance) {
            triggerMarketEvent();
        }
    }

    private void triggerMarketEvent() {
        if (failoverManager == null)
            return;
        String commodity = COMMODITIES[random.nextInt(COMMODITIES.length)];
        double current = failoverManager.getCommodityPrice(commodity);
        if (current <= 0.0)
            return;

        boolean positive = random.nextBoolean();
        double factor = positive ? (1.0 + (0.10 + random.nextDouble() * 0.10))
                : (1.0 - (0.08 + random.nextDouble() * 0.07));
        double newPrice = Math.max(MIN_PRICES.get(commodity), current * factor);
        failoverManager.updateCommodityPrice(commodity, newPrice);

        double percentChange = (factor - 1.0) * 100.0;
        String eventMsg;
        if (positive) {
            eventMsg = getNewsTextPositive(commodity, percentChange);
        } else {
            eventMsg = getNewsTextNegative(commodity, percentChange);
        }

        // Broadcast to players
        String prefix = Vault.getMessage("stocks.broadcast-prefix", "&d&l[Market] &f");
        String formattedBroadcast = prefix + eventMsg;
        net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> Bukkit.broadcastMessage(formattedBroadcast));

        // Discord Webhook Alert
        if (Vault.getDiscordManager() != null) {
            String webhookTemplate = Vault.getMessage("discord.webhook-stocks", "📈 **Stock Market News**\nCommodity **%stock%** price shifted to **%price%** (%change%)!");
            String content = webhookTemplate
                    .replace("%stock%", commodity.toUpperCase())
                    .replace("%price%", String.format("%.2f$", newPrice))
                    .replace("%change%", String.format("%+.1f%%", percentChange));
            Vault.getDiscordManager().sendWebhook("stocks", content);
        }

        TransactionFirewall firewall = Vault.getFirewall();
        if (firewall != null && firewall.getWebhookNotifier() != null) {
            firewall.getWebhookNotifier().sendAlertAsync("STOCKS_MARKET_EVENT", null, eventMsg,
                    positive ? 3066993 : 15158332);
        }
    }

    private String getNewsTextPositive(String commodity, double percent) {
        String name = commodity.toUpperCase();
        switch (commodity) {
            case "gold":
                return String.format(
                        Vault.getMessage("stocks.event.gold-up", "The Central Bank increases its Gold reserves! Gold rate climbs by +%.1f%%."), percent);
            case "iron":
                return String.format(
                        Vault.getMessage("stocks.event.iron-up", "Strong demand for iron for industrial projects! Iron rate increases by +%.1f%%."),
                        percent);
            case "emerald":
                return String.format(
                        Vault.getMessage("stocks.event.emerald-up", "A global trade agreement on emeralds boosts the market! Emerald gains +%.1f%%."),
                        percent);
            case "diamond":
                return String.format(
                        Vault.getMessage("stocks.event.diamond-up", "Global shortage of rough diamonds! Diamond rate climbs by +%.1f%%."),
                        percent);
            default:
                return String.format(
                        Vault.getMessage("stocks.event.generic-up", "The rate of %s is up by +%.1f%%!"), name, percent);
        }
    }

    private String getNewsTextNegative(String commodity, double percent) {
        String name = commodity.toUpperCase();
        switch (commodity) {
            case "gold":
                return String.format(
                        Vault.getMessage("stocks.event.gold-down", "Massive Gold sell-off by major investment funds! Gold rate drops by %.1f%%."),
                        percent);
            case "iron":
                return String.format(
                        Vault.getMessage("stocks.event.iron-down", "Discovery of a giant Iron deposit! Iron rate plummets by %.1f%%."),
                        percent);
            case "emerald":
                return String.format(
                        Vault.getMessage("stocks.event.emerald-down", "Villagers lower their trading taxes! Emerald rate drops by %.1f%%."),
                        percent);
            case "diamond":
                return String.format(
                        Vault.getMessage("stocks.event.diamond-down", "Successful industrial synthesis of perfect diamonds! Diamond rate drops by %.1f%%."),
                        percent);
            default:
                return String.format(
                        Vault.getMessage("stocks.event.generic-down", "The rate of %s is down by %.1f%%!"), name, percent);
        }
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
            List<Runnable> pendingTxTasks = new ArrayList<>();
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
                    EconomyResponse depRes = econ.depositPlayer(op, dividend);
                    if (depRes.transactionSuccess()) {
                        paidFromBank += dividend;
                        
                        final double finalDividend = dividend;
                        final String finalBank = bankName;
                        pendingTxTasks.add(() -> {
                            failoverManager.savePlayerTransaction(record.uuid, "DEPOSIT_DIVIDEND", "default", finalDividend,
                                    "Bank:" + finalBank);
                        });

                        // Notify player if online (run inline since we are on main thread)
                        Player p = Bukkit.getPlayer(record.uuid);
                        if (p != null && p.isOnline()) {
                            p.sendMessage(Vault.getMessage("dividends.received",
                                    "§a§l[Dividends] §aYou received §e%amount% §aof dividends for your shares in bank §e%bank%§a.")
                                    .replace("%amount%", econ.format(dividend))
                                    .replace("%bank%", bankName));
                        }
                    }
                }

                if (paidFromBank > 0.0) {
                    econ.bankWithdraw(bankName, paidFromBank);
                }
            }

            if (!pendingTxTasks.isEmpty()) {
                net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
                    for (Runnable task : pendingTxTasks) {
                        try {
                            task.run();
                        } catch (Exception ignored) {}
                    }
                });
            }
        });
    }
}

