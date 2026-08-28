package net.milkbowl.vault.util;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import org.bukkit.OfflinePlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight mock test kit for external plugin developers to write unit tests without requiring a running Spigot server.
 */
public final class VaultXTestKit {

    private VaultXTestKit() {}

    /**
     * Creates an in-memory mock MultiCurrencyEconomy instance pre-populated with starting balances.
     * @param defaultStartingBalance Starting balance for default currency
     * @return MultiCurrencyEconomy mock instance
     */
    public static MultiCurrencyEconomy createMockEconomy(double defaultStartingBalance) {
        return new MockMultiCurrencyEconomy(defaultStartingBalance);
    }

    private static class MockMultiCurrencyEconomy implements MultiCurrencyEconomy {

        private final Map<UUID, Map<String, Double>> balances = new ConcurrentHashMap<>();
        private final double startingBalance;

        public MockMultiCurrencyEconomy(double startingBalance) {
            this.startingBalance = startingBalance;
        }

        private double getBal(UUID uuid, String currency) {
            String curr = currency == null ? "default" : currency.toLowerCase();
            return balances.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).getOrDefault(curr, startingBalance);
        }

        private void setBal(UUID uuid, String currency, double val) {
            String curr = currency == null ? "default" : currency.toLowerCase();
            balances.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(curr, val);
        }

        @Override public boolean isEnabled() { return true; }
        @Override public String getName() { return "VaultX-MockTestKit"; }
        @Override public boolean hasBankSupport() { return false; }
        @Override public int fractionalDigits() { return 2; }
        @Override public String format(double amount) { return String.format("%.2f", amount); }
        @Override public String currencyNamePlural() { return "Dollars"; }
        @Override public String currencyNameSingular() { return "Dollar"; }

        @Override public boolean hasAccount(String playerName) { return true; }
        @Override public boolean hasAccount(OfflinePlayer player) { return true; }
        @Override public boolean hasAccount(String playerName, String worldName) { return true; }
        @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return true; }

        @Override public double getBalance(String playerName) { return startingBalance; }
        @Override public double getBalance(OfflinePlayer player) { return player != null ? getBal(player.getUniqueId(), "default") : startingBalance; }
        @Override public double getBalance(String playerName, String world) { return getBalance(playerName); }
        @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }

        @Override public boolean has(String playerName, double amount) { return getBalance(playerName) >= amount; }
        @Override public boolean has(OfflinePlayer player, double amount) { return getBalance(player) >= amount; }
        @Override public boolean has(String playerName, String worldName, double amount) { return has(playerName, amount); }
        @Override public boolean has(OfflinePlayer player, String worldName, double amount) { return has(player, amount); }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
            if (player == null) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player null");
            double current = getBal(player.getUniqueId(), "default");
            if (current >= amount) {
                double newBal = current - amount;
                setBal(player.getUniqueId(), "default", newBal);
                return new EconomyResponse(amount, newBal, EconomyResponse.ResponseType.SUCCESS, "");
            }
            return new EconomyResponse(0, current, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        }

        @Override public EconomyResponse withdrawPlayer(String playerName, double amount) { return new EconomyResponse(amount, startingBalance, EconomyResponse.ResponseType.SUCCESS, ""); }
        @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return withdrawPlayer(playerName, amount); }
        @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) { return withdrawPlayer(player, amount); }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
            if (player == null) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player null");
            double newBal = getBal(player.getUniqueId(), "default") + amount;
            setBal(player.getUniqueId(), "default", newBal);
            return new EconomyResponse(amount, newBal, EconomyResponse.ResponseType.SUCCESS, "");
        }

        @Override public EconomyResponse depositPlayer(String playerName, double amount) { return new EconomyResponse(amount, startingBalance, EconomyResponse.ResponseType.SUCCESS, ""); }
        @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return depositPlayer(playerName, amount); }
        @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) { return depositPlayer(player, amount); }

        @Override public EconomyResponse createBank(String name, String player) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not supported in mock"); }
        @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return createBank(name, ""); }
        @Override public EconomyResponse deleteBank(String name) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not supported in mock"); }
        @Override public EconomyResponse bankBalance(String name) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not supported in mock"); }
        @Override public EconomyResponse bankHas(String name, double amount) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not supported in mock"); }
        @Override public EconomyResponse bankWithdraw(String name, double amount) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not supported in mock"); }
        @Override public EconomyResponse bankDeposit(String name, double amount) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not supported in mock"); }
        @Override public EconomyResponse isBankOwner(String name, String playerName) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not supported in mock"); }
        @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return isBankOwner(name, ""); }
        @Override public EconomyResponse isBankMember(String name, String playerName) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not supported in mock"); }
        @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return isBankMember(name, ""); }
        @Override public List<String> getBanks() { return Collections.emptyList(); }
        @Override public boolean createPlayerAccount(String playerName) { return true; }
        @Override public boolean createPlayerAccount(OfflinePlayer player) { return true; }
        @Override public boolean createPlayerAccount(String playerName, String worldName) { return true; }
        @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return true; }

        @Override public List<String> getSupportedCurrencies() { return List.of("default", "gems", "tokens"); }
        @Override public double getCurrencyBalance(OfflinePlayer player, String currency) { return player != null ? getBal(player.getUniqueId(), currency) : startingBalance; }
        @Override public double getCurrencyBalance(String playerName, String currency) { return startingBalance; }

        @Override
        public EconomyResponse withdrawCurrencyPlayer(OfflinePlayer player, String currency, double amount) {
            if (player == null) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player null");
            double current = getBal(player.getUniqueId(), currency);
            if (current >= amount) {
                double newBal = current - amount;
                setBal(player.getUniqueId(), currency, newBal);
                return new EconomyResponse(amount, newBal, EconomyResponse.ResponseType.SUCCESS, "");
            }
            return new EconomyResponse(0, current, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        }

        @Override public EconomyResponse withdrawCurrencyPlayer(String playerName, String currency, double amount) { return new EconomyResponse(amount, startingBalance, EconomyResponse.ResponseType.SUCCESS, ""); }

        @Override
        public EconomyResponse depositCurrencyPlayer(OfflinePlayer player, String currency, double amount) {
            if (player == null) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player null");
            double newBal = getBal(player.getUniqueId(), currency) + amount;
            setBal(player.getUniqueId(), currency, newBal);
            return new EconomyResponse(amount, newBal, EconomyResponse.ResponseType.SUCCESS, "");
        }

        @Override public EconomyResponse depositCurrencyPlayer(String playerName, String currency, double amount) { return new EconomyResponse(amount, startingBalance, EconomyResponse.ResponseType.SUCCESS, ""); }
        @Override public boolean hasCurrencyAccount(OfflinePlayer player, String currency) { return true; }
        @Override public boolean hasCurrency(OfflinePlayer player, String currency, double amount) { return getCurrencyBalance(player, currency) >= amount; }
    }
}
