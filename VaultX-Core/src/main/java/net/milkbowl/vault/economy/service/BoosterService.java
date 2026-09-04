package net.milkbowl.vault.economy.service;

import net.milkbowl.vault.economy.VaultBoosterAPI;
import org.bukkit.OfflinePlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles global economy boosters, expirations, and multiplier calculations.
 */
public class BoosterService implements VaultBoosterAPI {

    private final Map<String, Double> globalBoosters = new ConcurrentHashMap<>();
    private final Map<String, Long> globalBoosterExpirations = new ConcurrentHashMap<>();

    public double getGlobalMultiplier(String currency) {
        String curr = currency == null ? "default" : currency.toLowerCase();
        Long exp = globalBoosterExpirations.get(curr);
        if (exp != null && System.currentTimeMillis() > exp) {
            globalBoosters.remove(curr);
            globalBoosterExpirations.remove(curr);
            return 1.0;
        }
        return globalBoosters.getOrDefault(curr, 1.0);
    }

    public void registerGlobalBooster(String currency, double multiplier, long durationMs) {
        String curr = currency == null ? "default" : currency.toLowerCase();
        if (multiplier <= 1.0) {
            globalBoosters.remove(curr);
            globalBoosterExpirations.remove(curr);
        } else {
            globalBoosters.put(curr, multiplier);
            globalBoosterExpirations.put(curr, System.currentTimeMillis() + durationMs);
        }
    }

    public double calculateBoostedAmount(OfflinePlayer player, String currency, double baseAmount) {
        return baseAmount * getGlobalMultiplier(currency);
    }

    public void clear() {
        globalBoosters.clear();
        globalBoosterExpirations.clear();
    }
}
