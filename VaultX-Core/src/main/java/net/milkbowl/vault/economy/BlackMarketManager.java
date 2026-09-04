package net.milkbowl.vault.economy;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.OfflinePlayer;
import org.bukkit.Bukkit;
import net.milkbowl.vault.economy.events.VaultBlackMarketLaunderEvent;

import net.milkbowl.vault.util.StripedLock;

public class BlackMarketManager implements VaultBlackMarketAPI {

    private final Plugin plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Double> dirtyBalances = new ConcurrentHashMap<>();
    private final StripedLock stripedLock = new StripedLock();
    private final BlackMarketItemHandler itemHandler;

    private java.util.concurrent.locks.ReentrantLock getPlayerLock(UUID uuid) {
        return stripedLock.getLock(uuid);
    }

    public static class LaunderingResult extends VaultBlackMarketAPI.LaunderingResult {
        public LaunderingResult(boolean success, boolean seized, double dirtyLaundered, double cleanReceived,
                double feePaid) {
            super(success, seized, dirtyLaundered, cleanReceived, feePaid);
        }
    }

    public BlackMarketManager(Plugin plugin) {
        this.plugin = plugin;
        this.itemHandler = new BlackMarketItemHandler(plugin);
        loadAllFromDb();
    }

    private void loadAllFromDb() {
        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
        if (fm != null) {
            Map<UUID, Double> loaded = fm.loadAllDirtyBalances();
            if (loaded != null && !loaded.isEmpty()) {
                dirtyBalances.putAll(loaded);
            }
        }
    }

    private void saveDirtyBalance(UUID uuid, double amount) {
        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
        if (fm != null && uuid != null) {
            fm.saveDirtyBalance(uuid, amount);
        }
    }

    public String getMode() {
        return plugin.getConfig().getString("blackmarket.dirty-money-mode", "CURRENCY").toUpperCase();
    }

    private double getOrLoadDirtyBalance(UUID uuid) {
        if (uuid == null) return 0.0;
        return dirtyBalances.computeIfAbsent(uuid, k -> {
            net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
            return fm != null ? fm.loadDirtyBalanceForPlayer(k) : 0.0;
        });
    }

    public double getDirtyBalance(Player player) {
        if (player == null)
            return 0.0;
        if (getMode().equals("ITEM")) {
            return itemHandler.countDirtyItems(player);
        } else {
            return getOrLoadDirtyBalance(player.getUniqueId());
        }
    }

    @Override
    public double getDirtyBalance(OfflinePlayer player) {
        if (player == null)
            return 0.0;
        if (player.isOnline() && player.getPlayer() != null) {
            return getDirtyBalance(player.getPlayer());
        }
        return getOrLoadDirtyBalance(player.getUniqueId());
    }

    @Override
    public void setDirtyBalance(OfflinePlayer player, double amount) {
        if (player == null || Double.isNaN(amount) || Double.isInfinite(amount))
            return;
        double val = Math.max(0.0, amount);
        dirtyBalances.put(player.getUniqueId(), val);
        saveDirtyBalance(player.getUniqueId(), val);
    }

    public void depositDirty(Player player, double amount) {
        if (player == null || Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0)
            return;
        if (getMode().equals("ITEM")) {
            itemHandler.giveDirtyItem(player, amount);
        } else {
            double current = getOrLoadDirtyBalance(player.getUniqueId());
            double val = current + amount;
            dirtyBalances.put(player.getUniqueId(), val);
            saveDirtyBalance(player.getUniqueId(), val);
        }
    }

    public boolean withdrawDirty(Player player, double amount) {
        if (player == null || Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0)
            return false;
        double current = getDirtyBalance(player);
        if (current < amount)
            return false;

        if (getMode().equals("ITEM")) {
            return itemHandler.removeDirtyItems(player, amount);
        } else {
            double val = current - amount;
            dirtyBalances.put(player.getUniqueId(), val);
            saveDirtyBalance(player.getUniqueId(), val);
            return true;
        }
    }

    @Override
    public void addDirtyMoney(OfflinePlayer player, double amount) {
        if (player == null || Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0)
            return;
        if (player.isOnline() && player.getPlayer() != null && getMode().equals("ITEM")) {
            itemHandler.giveDirtyItem(player.getPlayer(), amount);
        } else {
            double current = getOrLoadDirtyBalance(player.getUniqueId());
            double val = current + amount;
            dirtyBalances.put(player.getUniqueId(), val);
            saveDirtyBalance(player.getUniqueId(), val);
        }
    }

    @Override
    public LaunderingResult launder(Player player, double dirtyAmount) {
        return launder(player, dirtyAmount, net.milkbowl.vault.Vault.getWrappedEconomies().isEmpty() ? null
                : net.milkbowl.vault.Vault.getWrappedEconomies().get(0));
    }

    public LaunderingResult launder(Player player, double dirtyAmount, Economy econ) {
        if (player == null || dirtyAmount <= 0) {
            return new LaunderingResult(false, false, 0, 0, 0);
        }

        VaultBlackMarketLaunderEvent event = new VaultBlackMarketLaunderEvent(player, dirtyAmount);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return new LaunderingResult(false, false, 0, 0, 0);
        }
        dirtyAmount = event.getDirtyAmount();

        double maxAllowed = plugin.getConfig().getDouble("blackmarket.max-per-transaction", 500000.0);
        if (maxAllowed > 0 && dirtyAmount > maxAllowed) {
            return new LaunderingResult(false, false, 0, 0, 0);
        }

        double feePercent = plugin.getConfig().getDouble("blackmarket.laundering-fee-percent", 20.0);
        double seizureRisk = plugin.getConfig().getDouble("blackmarket.seizure-risk-percent", 5.0);

        synchronized (getPlayerLock(player.getUniqueId())) {
            if (getMode().equals("ITEM")) {
                int items = (int) Math.ceil(dirtyAmount / 100.0);
                dirtyAmount = items * 100.0;
            }

            double currentDirty = getDirtyBalance(player);
            if (currentDirty < dirtyAmount) {
                return new LaunderingResult(false, false, 0, 0, 0);
            }

            if (!withdrawDirty(player, dirtyAmount)) {
                return new LaunderingResult(false, false, 0, 0, 0);
            }

            boolean isSeized = (random.nextDouble() * 100.0) < seizureRisk;

            if (isSeized) {
                return new LaunderingResult(true, true, dirtyAmount, 0, 0);
            }

            double fee = dirtyAmount * (feePercent / 100.0);
            double cleanReceived = dirtyAmount - fee;

            if (econ != null) {
                econ.depositPlayer(player, cleanReceived);
            }

            return new LaunderingResult(true, false, dirtyAmount, cleanReceived, fee);
        }
    }

    public void cleanupPlayer(UUID uuid) {
        if (uuid != null) {
            Double amount = dirtyBalances.get(uuid);
            if (amount != null) {
                saveDirtyBalance(uuid, amount);
            }
            dirtyBalances.remove(uuid);
        }
    }

    public void close() {
        net.milkbowl.vault.redis.LocalFailoverManager fm = net.milkbowl.vault.Vault.getFailoverManager();
        if (fm != null) {
            for (Map.Entry<UUID, Double> entry : dirtyBalances.entrySet()) {
                fm.saveDirtyBalance(entry.getKey(), entry.getValue());
            }
        }
        dirtyBalances.clear();
    }
}
