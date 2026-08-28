package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;
import java.util.function.Supplier;

/**
 * Thread-safe transaction locking API to prevent duplicate transactions or double-spending race conditions.
 */
public interface VaultLockAPI {

    /**
     * Executes an operation while acquiring an exclusive lock on the player's account.
     * @param player OfflinePlayer
     * @param action Runnable action to execute under lock
     */
    void executeWithLock(OfflinePlayer player, Runnable action);

    /**
     * Executes a supplier function while acquiring an exclusive lock on the player's account.
     * @param player OfflinePlayer
     * @param supplier Supplier returning a result under lock
     * @param <T> Result type
     * @return Result of the supplier
     */
    <T> T computeWithLock(OfflinePlayer player, Supplier<T> supplier);
}
