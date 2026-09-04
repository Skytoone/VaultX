package net.milkbowl.vault.economy;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.persistence.repository.ExchangeRateRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ForexPriceEngine {

    private final Plugin plugin;
    private final ExchangeRateRepository repository;

    public ForexPriceEngine(Plugin plugin, ExchangeRateRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    public void fluctuateRates(Map<String, Double> rates, Map<String, Double> initialRates, double maxFluctuation, boolean marketEventsEnabled, double marketEventsChance) {
        Random rand = new Random();
        for (String currency : rates.keySet()) {
            if (currency.equals("default"))
                continue;

            double currentRate = rates.getOrDefault(currency.toLowerCase(), initialRates.getOrDefault(currency.toLowerCase(), 1.0));
            double pct = (rand.nextDouble() * 2.0 - 1.0) * (maxFluctuation / 100.0);
            double newRate = currentRate * (1.0 + pct);

            double initRate = initialRates.getOrDefault(currency.toLowerCase(), 1.0);
            double drift = (initRate - newRate) * 0.02;
            newRate += drift;

            double minBound = initRate * 0.01;
            double maxBound = initRate * 100.0;
            if (newRate < minBound)
                newRate = minBound;
            if (newRate > maxBound)
                newRate = maxBound;

            rates.put(currency.toLowerCase(), newRate);
            repository.saveRate(currency, newRate);
        }

        if (marketEventsEnabled && rand.nextDouble() * 100.0 < marketEventsChance) {
            List<String> customCurrencies = new ArrayList<>();
            for (String currency : rates.keySet()) {
                if (!currency.equalsIgnoreCase("default")) {
                    customCurrencies.add(currency);
                }
            }

            if (!customCurrencies.isEmpty()) {
                String selectedCurrency = customCurrencies.get(rand.nextInt(customCurrencies.size()));
                double currentRate = rates.getOrDefault(selectedCurrency.toLowerCase(), 1.0);
                double initRate = initialRates.getOrDefault(selectedCurrency.toLowerCase(), 1.0);

                boolean isBoom = rand.nextBoolean();
                double changePercent;
                double newRate;
                String eventMessage;

                if (isBoom) {
                    changePercent = 10.0 + rand.nextDouble() * 15.0;
                    newRate = currentRate * (1.0 + changePercent / 100.0);

                    String[] positiveTemplates = {
                            Vault.getMessage("forex.event.boom-1", "Economic boom on %currency% (+%change%%)!"),
                            Vault.getMessage("forex.event.boom-2", "Exchange rates soaring on %currency% (+%change%%)!"),
                            Vault.getMessage("forex.event.boom-3", "Massive positive speculation on %currency% (+%change%%)!")
                    };
                    String rawTemplate = positiveTemplates[rand.nextInt(positiveTemplates.length)];
                    eventMessage = rawTemplate
                            .replace("%currency%",
                                    selectedCurrency.substring(0, 1).toUpperCase() + selectedCurrency.substring(1))
                            .replace("%change%", String.format("%.1f", changePercent));
                } else {
                    changePercent = 10.0 + rand.nextDouble() * 15.0;
                    newRate = currentRate * (1.0 - changePercent / 100.0);

                    String[] negativeTemplates = {
                            Vault.getMessage("forex.event.krach-1", "Market crash on %currency% (-%change%%)!"),
                            Vault.getMessage("forex.event.krach-2", "Sudden recession on %currency% (-%change%%)!"),
                            Vault.getMessage("forex.event.krach-3", "Panic selling on %currency% (-%change%%)!")
                    };
                    String rawTemplate = negativeTemplates[rand.nextInt(negativeTemplates.length)];
                    eventMessage = rawTemplate
                            .replace("%currency%",
                                    selectedCurrency.substring(0, 1).toUpperCase() + selectedCurrency.substring(1))
                            .replace("%change%", String.format("%.1f", changePercent));
                }

                double minBound = initRate * 0.01;
                double maxBound = initRate * 100.0;
                if (newRate < minBound)
                    newRate = minBound;
                if (newRate > maxBound)
                    newRate = maxBound;

                rates.put(selectedCurrency.toLowerCase(), newRate);
                repository.saveRate(selectedCurrency, newRate);

                net.milkbowl.vault.security.TransactionFirewall firewall = net.milkbowl.vault.Vault.getFirewall();
                if (firewall != null && firewall.getWebhookNotifier() != null) {
                    firewall.getWebhookNotifier().sendAlertAsync("FOREX_MARKET_EVENT", null, eventMessage, isBoom ? 3066993 : 15158332);
                }

                net.milkbowl.vault.util.FoliaScheduler.runSync(plugin, () -> {
                    String prefix = Vault.getMessage("forex.broadcast-prefix", "&6&l[VaultX Forex] &e📢 ÉVÉNEMENT BOURSIER : ");
                    String colorCode = isBoom ? "§a" : "§c";
                    String announcement = prefix + colorCode + eventMessage;

                    Bukkit.broadcastMessage(announcement);

                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (isBoom) {
                            net.milkbowl.vault.util.VaultXVisuals.playSuccessSound(p);
                        } else {
                            net.milkbowl.vault.util.VaultXVisuals.playFailureSound(p);
                        }
                    }
                });
            }
        }

        plugin.getLogger().info("[VaultX Forex] Exchange rates fluctuated dynamically.");
    }
}
