package net.milkbowl.vault.economy.events;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Asynchronous event dispatched when a player reaches a financial milestone threshold.
 */
public class VaultMilestoneReachedEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final OfflinePlayer player;
    private final String currency;
    private final double milestoneAmount;
    private final String rewardCommand;

    public VaultMilestoneReachedEvent(OfflinePlayer player, String currency, double milestoneAmount, String rewardCommand) {
        super(true); // Asynchronous
        this.player = player;
        this.currency = currency;
        this.milestoneAmount = milestoneAmount;
        this.rewardCommand = rewardCommand;
    }

    public OfflinePlayer getPlayer() {
        return player;
    }

    public String getCurrency() {
        return currency;
    }

    public double getMilestoneAmount() {
        return milestoneAmount;
    }

    public String getRewardCommand() {
        return rewardCommand;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
