package net.milkbowl.vault.economy.events;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event fired when a player is frozen by the Transaction Firewall anti-exploit system.
 */
public class VaultPlayerFreezeEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    private boolean cancelled = false;

    private final OfflinePlayer player;
    private final String reason;

    public VaultPlayerFreezeEvent(OfflinePlayer player, String reason) {
        super(false); // Synchronous
        this.player = player;
        this.reason = reason;
    }

    public OfflinePlayer getPlayer() {
        return player;
    }

    public String getReason() {
        return reason;
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
