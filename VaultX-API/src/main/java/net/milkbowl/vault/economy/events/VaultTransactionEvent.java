package net.milkbowl.vault.economy.events;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Asynchronous event dispatched when an economy transaction takes place via Modern Vault.
 */
public class VaultTransactionEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    
    private final OfflinePlayer player;
    private final String playerName;
    private final double amount;
    private final TransactionType type;
    private final String callerPlugin;

    public enum TransactionType {
        DEPOSIT,
        WITHDRAW
    }

    public VaultTransactionEvent(OfflinePlayer player, double amount, TransactionType type, String callerPlugin) {
        super(true); // Is Asynchronous
        this.player = player;
        this.playerName = player != null ? player.getName() : null;
        this.amount = amount;
        this.type = type;
        this.callerPlugin = callerPlugin;
    }

    public VaultTransactionEvent(String playerName, double amount, TransactionType type, String callerPlugin) {
        super(true); // Is Asynchronous
        this.player = null;
        this.playerName = playerName;
        this.amount = amount;
        this.type = type;
        this.callerPlugin = callerPlugin;
    }

    public OfflinePlayer getPlayer() {
        return player;
    }

    public String getPlayerName() {
        return playerName;
    }

    public double getAmount() {
        return amount;
    }

    public TransactionType getType() {
        return type;
    }

    public String getCallerPlugin() {
        return callerPlugin;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
