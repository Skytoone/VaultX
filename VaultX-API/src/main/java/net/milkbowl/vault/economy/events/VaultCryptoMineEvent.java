package net.milkbowl.vault.economy.events;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Asynchronous event dispatched when a player successfully mines crypto tokens.
 */
public class VaultCryptoMineEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final OfflinePlayer player;
    private final String tokenSymbol;
    private final double minedAmount;
    private final double difficulty;

    public VaultCryptoMineEvent(OfflinePlayer player, String tokenSymbol, double minedAmount, double difficulty) {
        super(true); // Asynchronous
        this.player = player;
        this.tokenSymbol = tokenSymbol;
        this.minedAmount = minedAmount;
        this.difficulty = difficulty;
    }

    public OfflinePlayer getPlayer() {
        return player;
    }

    public String getTokenSymbol() {
        return tokenSymbol;
    }

    public double getMinedAmount() {
        return minedAmount;
    }

    public double getDifficulty() {
        return difficulty;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
