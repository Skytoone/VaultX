package net.milkbowl.vault.util;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-size striped lock pool to eliminate lock allocation overhead and prevent memory leaks.
 */
public final class StripedLock {

    private static final int STRIPE_COUNT = 64;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPE_COUNT];

    public StripedLock() {
        for (int i = 0; i < STRIPE_COUNT; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * Gets the ReentrantLock assigned to a specific UUID hash stripe.
     *
     * @param uuid Target UUID
     * @return ReentrantLock stripe
     */
    public ReentrantLock getLock(UUID uuid) {
        if (uuid == null) {
            return locks[0];
        }
        int index = (uuid.hashCode() & 0x7FFFFFFF) & (STRIPE_COUNT - 1);
        return locks[index];
    }

    /**
     * Gets the ReentrantLock assigned to a specific String key stripe.
     *
     * @param key Target key
     * @return ReentrantLock stripe
     */
    public ReentrantLock getLock(String key) {
        if (key == null) {
            return locks[0];
        }
        int index = (key.hashCode() & 0x7FFFFFFF) & (STRIPE_COUNT - 1);
        return locks[index];
    }
}
