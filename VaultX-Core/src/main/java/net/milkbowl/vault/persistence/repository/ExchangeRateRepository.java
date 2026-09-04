package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.redis.VaultRedisManager;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.Map;

public class ExchangeRateRepository {

    private final Plugin plugin;

    public ExchangeRateRepository(Plugin plugin) {
        this.plugin = plugin;
    }

    public Map<String, Double> loadRates() {
        Map<String, Double> rates = new HashMap<>();
        Map<String, Double> dbRates = Vault.getFailoverManager().getExchangeRates();
        if (dbRates != null && !dbRates.isEmpty()) {
            for (Map.Entry<String, Double> entry : dbRates.entrySet()) {
                rates.put(entry.getKey().toLowerCase(), entry.getValue());
            }
        }

        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            try (Jedis jedis = redis.getPool().getResource()) {
                Map<String, String> redisRates = jedis.hgetAll("vaultx:exchange_rates");
                if (redisRates != null && !redisRates.isEmpty()) {
                    for (Map.Entry<String, String> entry : redisRates.entrySet()) {
                        try {
                            rates.put(entry.getKey().toLowerCase(), Double.parseDouble(entry.getValue()));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[VaultX Forex] Failed to load rates from Redis: " + e.getMessage());
            }
        }
        return rates;
    }

    public void saveRate(String currency, double rate) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            Vault.getFailoverManager().saveExchangeRate(currency, rate);

            VaultRedisManager redis = VaultRedisManager.getInstance();
            if (redis != null && redis.isOnline()) {
                try (Jedis jedis = redis.getPool().getResource()) {
                    jedis.hset("vaultx:exchange_rates", currency.toLowerCase(), String.valueOf(rate));
                    jedis.publish("vaultx:forex:update", currency.toLowerCase() + ":" + rate);
                } catch (Exception e) {
                    plugin.getLogger().warning("[VaultX Forex] Failed to save rate to Redis: " + e.getMessage());
                }
            }
        });
    }
}
