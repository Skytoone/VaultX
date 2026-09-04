package net.milkbowl.vault.economy;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.events.VaultStockPriceChangeEvent;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.security.TransactionFirewall;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class MarketPriceEngine {

    private final Plugin plugin;
    private final LocalFailoverManager failoverManager;
    private final Random random = new Random();

    public static final String[] COMMODITIES = { "gold", "iron", "emerald", "diamond" };
    public static final Map<String, Double> DEFAULT_PRICES = new HashMap<>();
    public static final Map<String, Double> MIN_PRICES = new HashMap<>();

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

    public MarketPriceEngine(Plugin plugin, LocalFailoverManager failoverManager) {
        this.plugin = plugin;
        this.failoverManager = failoverManager;
    }

    public void initPrices() {
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

    public void fluctuatePrices() {
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
            final double oldPrice = current;
            final double targetNewPrice = newPrice;
            net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                Bukkit.getPluginManager().callEvent(new VaultStockPriceChangeEvent(commodity, oldPrice, targetNewPrice));
            });
        }

        // 2. Chance of a market news event
        double eventChance = plugin.getConfig().getDouble("stocks.event-chance-percent", 15.0) / 100.0;
        if (random.nextDouble() < eventChance) {
            triggerMarketEvent();
        }
    }

    public void triggerMarketEvent() {
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
        net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
            Bukkit.getPluginManager().callEvent(new VaultStockPriceChangeEvent(commodity, current, newPrice));
        });

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
}
