package net.milkbowl.vault.economy.events;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Asynchronous event dispatched when a recurring subscription payment is successfully processed in VaultX.
 */
public class VaultSubscriptionRenewEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final OfflinePlayer player;
    private final String subscriptionId;
    private final String currency;
    private final double amount;
    private final boolean success;

    public VaultSubscriptionRenewEvent(OfflinePlayer player, String subscriptionId, String currency, double amount, boolean success) {
        super(true); // Is Asynchronous
        this.player = player;
        this.subscriptionId = subscriptionId;
        this.currency = currency != null ? currency : "default";
        this.amount = amount;
        this.success = success;
    }

    public OfflinePlayer getPlayer() {
        return player;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public String getCurrency() {
        return currency;
    }

    public double getAmount() {
        return amount;
    }

    public boolean isSuccess() {
        return success;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
