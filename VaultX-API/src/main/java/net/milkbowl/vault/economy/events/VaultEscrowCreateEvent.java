package net.milkbowl.vault.economy.events;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event fired asynchronously when a player initiates an Escrow transaction.
 */
public class VaultEscrowCreateEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    private boolean cancelled = false;

    private final Player sender;
    private final OfflinePlayer receiver;
    private double amount;
    private final String currency;
    private long timeoutSec;

    public VaultEscrowCreateEvent(Player sender, OfflinePlayer receiver, double amount, String currency, long timeoutSec) {
        super(true); // Is Asynchronous
        this.sender = sender;
        this.receiver = receiver;
        this.amount = amount;
        this.currency = currency != null ? currency : "default";
        this.timeoutSec = timeoutSec;
    }

    public Player getSender() {
        return sender;
    }

    public OfflinePlayer getReceiver() {
        return receiver;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public long getTimeoutSec() {
        return timeoutSec;
    }

    public void setTimeoutSec(long timeoutSec) {
        this.timeoutSec = timeoutSec;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
