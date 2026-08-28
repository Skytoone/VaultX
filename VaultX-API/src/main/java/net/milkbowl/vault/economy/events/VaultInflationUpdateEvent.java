package net.milkbowl.vault.economy.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event dispatched when VaultX recalculates total money supply and updates inflation index multipliers.
 */
public class VaultInflationUpdateEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final String currency;
    private final double oldMultiplier;
    private final double newMultiplier;
    private final double currentSupply;
    private final double baselineSupply;

    public VaultInflationUpdateEvent(String currency, double oldMultiplier, double newMultiplier, double currentSupply, double baselineSupply) {
        super(true); // Is Asynchronous
        this.currency = currency;
        this.oldMultiplier = oldMultiplier;
        this.newMultiplier = newMultiplier;
        this.currentSupply = currentSupply;
        this.baselineSupply = baselineSupply;
    }

    public String getCurrency() {
        return currency;
    }

    public double getOldMultiplier() {
        return oldMultiplier;
    }

    public double getNewMultiplier() {
        return newMultiplier;
    }

    public double getCurrentSupply() {
        return currentSupply;
    }

    public double getBaselineSupply() {
        return baselineSupply;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
