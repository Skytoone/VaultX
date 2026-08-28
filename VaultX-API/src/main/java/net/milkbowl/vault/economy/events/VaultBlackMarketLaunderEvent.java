package net.milkbowl.vault.economy.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event fired when a player attempts to launder dirty money on the Black Market.
 */
public class VaultBlackMarketLaunderEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    private boolean cancelled = false;

    private final Player player;
    private double dirtyAmount;

    public VaultBlackMarketLaunderEvent(Player player, double dirtyAmount) {
        super(false); // Synchronous
        this.player = player;
        this.dirtyAmount = dirtyAmount;
    }

    public Player getPlayer() {
        return player;
    }

    public double getDirtyAmount() {
        return dirtyAmount;
    }

    public void setDirtyAmount(double dirtyAmount) {
        this.dirtyAmount = dirtyAmount;
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
