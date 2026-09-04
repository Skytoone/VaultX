package net.milkbowl.vault.economy.service;

import net.milkbowl.vault.economy.VaultLockAPI;
import net.milkbowl.vault.util.StripedLock;
import org.bukkit.OfflinePlayer;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Handles thread-safe atomic lock execution for economy player accounts.
 */
public class EconomyLockService implements VaultLockAPI {

    private final StripedLock stripedLock = new StripedLock();

    public void executeWithLock(OfflinePlayer player, Runnable action) {
        if (player == null || action == null) return;
        ReentrantLock lock = stripedLock.getLock(player.getUniqueId());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public <T> T computeWithLock(OfflinePlayer player, Supplier<T> supplier) {
        if (player == null || supplier == null) return null;
        ReentrantLock lock = stripedLock.getLock(player.getUniqueId());
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }
}
