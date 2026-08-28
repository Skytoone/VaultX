package net.milkbowl.vault.economy;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.redis.VaultRedisManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import redis.clients.jedis.Jedis;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ExchangeRateManager implements CurrencyExchangeAPI {

    private final Plugin plugin;
    private final Map<String, Double> rates = new ConcurrentHashMap<>();
    private final Map<String, Double> initialRates = new HashMap<>();

    private boolean enabled = false;
    private int updateInterval = 15;
    private double maxFluctuation = 3.5;
    private double demandFactor = 0.001;
    private boolean marketEventsEnabled = true;
    private double marketEventsChance = 10.0;
    private org.bukkit.scheduler.BukkitTask fluctuationTask;

    public ExchangeRateManager(Plugin plugin) {
        this.plugin = plugin;
        loadConfig();
        if (enabled) {
            loadRates();
            startFluctuationScheduler();
        }
    }

    public void close() {
        if (fluctuationTask != null) {
            fluctuationTask.cancel();
            fluctuationTask = null;
        }
    }

    private void loadConfig() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("currency-exchange");
        if (sec != null) {
            this.enabled = sec.getBoolean("dynamic-rates.enabled", false);
            this.updateInterval = sec.getInt("dynamic-rates.update-interval-minutes", 15);
            this.maxFluctuation = sec.getDouble("dynamic-rates.max-fluctuation-percent", 3.5);
            this.demandFactor = sec.getDouble("dynamic-rates.demand-factor", 0.001);
            this.marketEventsEnabled = sec.getBoolean("dynamic-rates.market-events.enabled", true);
            this.marketEventsChance = sec.getDouble("dynamic-rates.market-events.chance-percent", 10.0);

            ConfigurationSection rateSec = sec.getConfigurationSection("rates");
            if (rateSec != null) {
                for (String key : rateSec.getKeys(false)) {
                    double rate = rateSec.getDouble(key, 1.0);
                    initialRates.put(key.toLowerCase(), rate);
                    rates.put(key.toLowerCase(), rate);
                }
            }
        }
        // Ensure default always exists at 1.0
        initialRates.put("default", 1.0);
        rates.put("default", 1.0);
    }

    private void loadRates() {
        // 1. Load from DB
        Map<String, Double> dbRates = Vault.getFailoverManager().getExchangeRates();
        if (dbRates != null && !dbRates.isEmpty()) {
            for (Map.Entry<String, Double> entry : dbRates.entrySet()) {
                rates.put(entry.getKey().toLowerCase(), entry.getValue());
            }
        }

        // 2. Load from Redis if online
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            try (Jedis jedis = redis.getPool().getResource()) {
                Map<String, String> redisRates = jedis.hgetAll("vaultx:exchange_rates");
                if (redisRates != null && !redisRates.isEmpty()) {
                    for (Map.Entry<String, String> entry : redisRates.entrySet()) {
                        try {
                            rates.put(entry.getKey().toLowerCase(), Double.parseDouble(entry.getValue()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[VaultX Forex] Failed to load rates from Redis: " + e.getMessage());
            }
        }
    }

    private void saveRate(String currency, double rate) {
        rates.put(currency.toLowerCase(), rate);

        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            // Save to Local DB
            Vault.getFailoverManager().saveExchangeRate(currency, rate);

            // Save to Redis
            VaultRedisManager redis = VaultRedisManager.getInstance();
            if (redis != null && redis.isOnline()) {
                try (Jedis jedis = redis.getPool().getResource()) {
                    jedis.hset("vaultx:exchange_rates", currency.toLowerCase(), String.valueOf(rate));
                    // Publish update channel event
                    jedis.publish("vaultx:forex:update", currency.toLowerCase() + ":" + rate);
                } catch (Exception e) {
                    plugin.getLogger().warning("[VaultX Forex] Failed to save rate to Redis: " + e.getMessage());
                }
            }
        });
    }

    public void updateRateFromNetwork(String currency, double rate) {
        rates.put(currency.toLowerCase(), rate);
    }

    public double getRate(String currency) {
        if (currency == null)
            return 1.0;
        String cur = currency.toLowerCase();
        if (cur.equals("default"))
            return 1.0;
        return rates.getOrDefault(cur, initialRates.getOrDefault(cur, 1.0));
    }

    public Map<String, Double> getRates() {
        return Collections.unmodifiableMap(rates);
    }

    private void startFluctuationScheduler() {
        long ticks = updateInterval * 60L * 20L;
        fluctuationTask = net.milkbowl.vault.util.FoliaScheduler.runTimerAsync(plugin, () -> {
            Random rand = new Random();
            for (String currency : rates.keySet()) {
                if (currency.equals("default"))
                    continue;

                double currentRate = getRate(currency);
                double pct = (rand.nextDouble() * 2.0 - 1.0) * (maxFluctuation / 100.0);
                double newRate = currentRate * (1.0 + pct);

                // Add mean reversion towards initial rate
                double initRate = initialRates.getOrDefault(currency, 1.0);
                double drift = (initRate - newRate) * 0.02; // Slow pull towards base price
                newRate += drift;

                // Ensure rate stays within bounds (0.01x to 100x of initial rate)
                double minBound = initRate * 0.01;
                double maxBound = initRate * 100.0;
                if (newRate < minBound)
                    newRate = minBound;
                if (newRate > maxBound)
                    newRate = maxBound;

                saveRate(currency, newRate);
            }

            // Random Market Events (Krach boursier, Boom économique, etc.)
            if (marketEventsEnabled && rand.nextDouble() * 100.0 < marketEventsChance) {
                List<String> customCurrencies = new ArrayList<>();
                for (String currency : rates.keySet()) {
                    if (!currency.equalsIgnoreCase("default")) {
                        customCurrencies.add(currency);
                    }
                }

                if (!customCurrencies.isEmpty()) {
                    String selectedCurrency = customCurrencies.get(rand.nextInt(customCurrencies.size()));
                    double currentRate = getRate(selectedCurrency);
                    double initRate = initialRates.getOrDefault(selectedCurrency, 1.0);

                    boolean isBoom = rand.nextBoolean();
                    double changePercent;
                    double newRate;
                    String eventMessage;

                    if (isBoom) {
                        changePercent = 10.0 + rand.nextDouble() * 15.0; // +10% to +25%
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
                        changePercent = 10.0 + rand.nextDouble() * 15.0; // -10% to -25%
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

                    saveRate(selectedCurrency, newRate);

                    // Send Discord webhook alert asynchronously
                    net.milkbowl.vault.security.TransactionFirewall firewall = net.milkbowl.vault.Vault.getFirewall();
                    if (firewall != null && firewall.getWebhookNotifier() != null) {
                        firewall.getWebhookNotifier().sendAlertAsync("FOREX_MARKET_EVENT", null, eventMessage, isBoom ? 3066993 : 15158332);
                    }

                    // Broadcast alert and play sounds on Main Thread
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
        }, ticks, ticks);
    }

    public static class ConversionResult {
        public final boolean success;
        public final String message;
        public final double receivedAmount;
        public final double newFromBalance;
        public final double newToBalance;

        public ConversionResult(boolean success, String message, double receivedAmount, double newFromBalance,
                double newToBalance) {
            this.success = success;
            this.message = message;
            this.receivedAmount = receivedAmount;
            this.newFromBalance = newFromBalance;
            this.newToBalance = newToBalance;
        }
    }

    public ConversionResult convert(Player player, String fromCurrency, String toCurrency, double amount) {
        if (player == null || fromCurrency == null || toCurrency == null) {
            return new ConversionResult(false, "Invalid parameters.", 0, 0, 0);
        }

        String from = fromCurrency.toLowerCase();
        String to = toCurrency.toLowerCase();

        if (from.equals(to)) {
            return new ConversionResult(false, "Cannot convert to the same currency.", 0, 0, 0);
        }

        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return new ConversionResult(false, "Economy provider not found.", 0, 0, 0);
        }
        Economy econ = rsp.getProvider();

        // 1. Get rates
        double fromRate = getRate(from); // value in default currency
        double toRate = getRate(to); // value in default currency

        // 2. Validate player has enough fromCurrency
        double playerFromBal = 0;
        if (from.equals("default")) {
            playerFromBal = econ.getBalance(player);
        } else if (econ instanceof MultiCurrencyEconomy) {
            playerFromBal = ((MultiCurrencyEconomy) econ).getCurrencyBalance(player, from);
        }

        if (playerFromBal < amount) {
            return new ConversionResult(false, "Insufficient funds in " + fromCurrency.toUpperCase() + ".", 0,
                    playerFromBal, 0);
        }

        // 3. Calculate value in default currency
        double defaultVal = amount * fromRate;

        // Apply Exchange Tax (configured in central-bank.taxes)
        double tax = 0.0;
        boolean taxesEnabled = plugin.getConfig().getBoolean("central-bank.taxes.enabled", false);
        double taxPct = plugin.getConfig().getDouble("central-bank.taxes.exchange-tax-percent", 1.0);
        if (taxesEnabled && taxPct > 0) {
            tax = defaultVal * (taxPct / 100.0);
            defaultVal -= tax;
        }

        // Convert to target currency
        double targetVal = defaultVal / toRate;

        // 4. Perform withdrawals & deposits
        EconomyResponse wRes;
        if (from.equals("default")) {
            wRes = econ.withdrawPlayer(player, amount);
        } else if (econ instanceof MultiCurrencyEconomy) {
            wRes = ((MultiCurrencyEconomy) econ).withdrawCurrencyPlayer(player, from, amount);
        } else {
            return new ConversionResult(false, "Multi-currency transactions are not supported.", 0, 0, 0);
        }

        if (!wRes.transactionSuccess()) {
            return new ConversionResult(false, "Withdrawal failed: " + wRes.errorMessage, 0, playerFromBal, 0);
        }

        // Deposit target currency
        EconomyResponse dRes;
        if (to.equals("default")) {
            dRes = econ.depositPlayer(player, targetVal);
        } else if (econ instanceof MultiCurrencyEconomy) {
            dRes = ((MultiCurrencyEconomy) econ).depositCurrencyPlayer(player, to, targetVal);
        } else {
            // Rollback withdrawal
            if (from.equals("default")) {
                econ.depositPlayer(player, amount);
            } else {
                ((MultiCurrencyEconomy) econ).depositCurrencyPlayer(player, from, amount);
            }
            return new ConversionResult(false, "Deposit failed: target currency unsupported.", 0, 0, 0);
        }

        if (!dRes.transactionSuccess()) {
            // Rollback withdrawal
            if (from.equals("default")) {
                econ.depositPlayer(player, amount);
            } else {
                ((MultiCurrencyEconomy) econ).depositCurrencyPlayer(player, from, amount);
            }
            return new ConversionResult(false, "Deposit failed: " + dRes.errorMessage, 0, playerFromBal, 0);
        }

        // Deposit taxes to Treasury if enabled
        if (tax > 0) {
            String treasury = plugin.getConfig().getString("central-bank.taxes.treasury-account", "tresor_public");
            econ.bankDeposit(treasury, tax);
        }

        // 5. Apply Supply & Demand rate adjustment
        // Sell from -> price of from goes down
        if (!from.equals("default")) {
            double current = getRate(from);
            double reduction = current * (amount * demandFactor / 100.0);
            double newRate = Math.max(current - reduction, initialRates.getOrDefault(from, 1.0) * 0.05);
            saveRate(from, newRate);
        }

        // Buy to -> price of to goes up
        if (!to.equals("default")) {
            double current = getRate(to);
            double increase = current * (targetVal * demandFactor / 100.0);
            double newRate = Math.min(current + increase, initialRates.getOrDefault(to, 1.0) * 20.0);
            saveRate(to, newRate);
        }

        double finalFromBal = from.equals("default") ? econ.getBalance(player)
                : ((MultiCurrencyEconomy) econ).getCurrencyBalance(player, from);
        double finalToBal = to.equals("default") ? econ.getBalance(player)
                : ((MultiCurrencyEconomy) econ).getCurrencyBalance(player, to);

        return new ConversionResult(true,
                "Successfully exchanged " + amount + " " + fromCurrency.toUpperCase() + " to "
                        + String.format("%.4f", targetVal) + " " + toCurrency.toUpperCase(),
                targetVal, finalFromBal, finalToBal);
    }

    @Override
    public double getExchangeRate(String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null) return 1.0;
        String from = fromCurrency.toLowerCase();
        String to = toCurrency.toLowerCase();
        if (from.equals(to)) return 1.0;
        double fromRate = rates.getOrDefault(from, 1.0);
        double toRate = rates.getOrDefault(to, 1.0);
        if (toRate == 0.0) return 1.0;
        return fromRate / toRate;
    }

    @Override
    public double convert(String fromCurrency, String toCurrency, double amount) {
        return amount * getExchangeRate(fromCurrency, toCurrency);
    }

    @Override
    public Map<String, Double> getAllExchangeRates() {
        return Collections.unmodifiableMap(new HashMap<>(rates));
    }
}

