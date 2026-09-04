package net.milkbowl.vault.economy;

import net.milkbowl.vault.persistence.repository.ExchangeRateRepository;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ExchangeRateManager implements CurrencyExchangeAPI {

    private final Plugin plugin;
    private final Map<String, Double> rates = new ConcurrentHashMap<>();
    private final Map<String, Double> initialRates = new HashMap<>();
    private final ExchangeRateRepository repository;
    private final ForexPriceEngine priceEngine;

    private boolean enabled = false;
    private int updateInterval = 15;
    private double maxFluctuation = 3.5;
    private double demandFactor = 0.001;
    private boolean marketEventsEnabled = true;
    private double marketEventsChance = 10.0;
    private org.bukkit.scheduler.BukkitTask fluctuationTask;

    public ExchangeRateManager(Plugin plugin) {
        this.plugin = plugin;
        this.repository = new ExchangeRateRepository(plugin);
        this.priceEngine = new ForexPriceEngine(plugin, repository);
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
        initialRates.put("default", 1.0);
        rates.put("default", 1.0);
    }

    private void loadRates() {
        Map<String, Double> loaded = repository.loadRates();
        if (loaded != null && !loaded.isEmpty()) {
            rates.putAll(loaded);
        }
    }

    private void saveRate(String currency, double rate) {
        rates.put(currency.toLowerCase(), rate);
        repository.saveRate(currency, rate);
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
            priceEngine.fluctuateRates(rates, initialRates, maxFluctuation, marketEventsEnabled, marketEventsChance);
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

        double fromRate = getRate(from);
        double toRate = getRate(to);

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

        double defaultVal = amount * fromRate;

        double tax = 0.0;
        boolean taxesEnabled = plugin.getConfig().getBoolean("central-bank.taxes.enabled", false);
        double taxPct = plugin.getConfig().getDouble("central-bank.taxes.exchange-tax-percent", 1.0);
        if (taxesEnabled && taxPct > 0) {
            tax = defaultVal * (taxPct / 100.0);
            defaultVal -= tax;
        }

        double targetVal = defaultVal / toRate;

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

        EconomyResponse dRes;
        if (to.equals("default")) {
            dRes = econ.depositPlayer(player, targetVal);
        } else if (econ instanceof MultiCurrencyEconomy) {
            dRes = ((MultiCurrencyEconomy) econ).depositCurrencyPlayer(player, to, targetVal);
        } else {
            if (from.equals("default")) {
                econ.depositPlayer(player, amount);
            } else {
                ((MultiCurrencyEconomy) econ).depositCurrencyPlayer(player, from, amount);
            }
            return new ConversionResult(false, "Deposit failed: target currency unsupported.", 0, 0, 0);
        }

        if (!dRes.transactionSuccess()) {
            if (from.equals("default")) {
                econ.depositPlayer(player, amount);
            } else {
                ((MultiCurrencyEconomy) econ).depositCurrencyPlayer(player, from, amount);
            }
            return new ConversionResult(false, "Deposit failed: " + dRes.errorMessage, 0, playerFromBal, 0);
        }

        if (tax > 0) {
            String treasury = plugin.getConfig().getString("central-bank.taxes.treasury-account", "tresor_public");
            econ.bankDeposit(treasury, tax);
        }

        if (!from.equals("default")) {
            double current = getRate(from);
            double reduction = current * (amount * demandFactor / 100.0);
            double newRate = Math.max(current - reduction, initialRates.getOrDefault(from, 1.0) * 0.05);
            saveRate(from, newRate);
        }

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
