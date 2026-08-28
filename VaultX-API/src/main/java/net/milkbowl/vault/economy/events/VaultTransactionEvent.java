package net.milkbowl.vault.economy.events;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Asynchronous event dispatched when an economy transaction takes place via Modern VaultX.
 */
public class VaultTransactionEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    
    private final OfflinePlayer player;
    private final String playerName;
    private final OfflinePlayer targetPlayer;
    private final String targetName;
    private final double amount;
    private final String currency;
    private final TransactionType type;
    private final String callerPlugin;
    private final String reason;
    private final double newBalance;

    public enum TransactionType {
        DEPOSIT,
        WITHDRAW
    }

    /**
     * Primary full constructor for VaultX transactions.
     */
    public VaultTransactionEvent(OfflinePlayer player, OfflinePlayer targetPlayer, double amount, String currency, TransactionType type, String callerPlugin, String reason, double newBalance) {
        super(true); // Is Asynchronous
        this.player = player;
        this.playerName = player != null ? player.getName() : null;
        this.targetPlayer = targetPlayer;
        this.targetName = targetPlayer != null ? targetPlayer.getName() : null;
        this.amount = amount;
        this.currency = currency != null ? currency : "default";
        this.type = type;
        this.callerPlugin = callerPlugin != null ? callerPlugin : "VaultX";
        this.reason = reason;
        this.newBalance = newBalance;
    }

    public VaultTransactionEvent(OfflinePlayer player, double amount, String currency, TransactionType type, String callerPlugin, double newBalance) {
        this(player, null, amount, currency, type, callerPlugin, null, newBalance);
    }

    public VaultTransactionEvent(OfflinePlayer player, double amount, TransactionType type, String callerPlugin) {
        this(player, null, amount, "default", type, callerPlugin, null, -1.0);
    }

    public VaultTransactionEvent(String playerName, double amount, TransactionType type, String callerPlugin) {
        super(true); // Is Asynchronous
        this.player = null;
        this.playerName = playerName;
        this.targetPlayer = null;
        this.targetName = null;
        this.amount = amount;
        this.currency = "default";
        this.type = type;
        this.callerPlugin = callerPlugin != null ? callerPlugin : "VaultX";
        this.reason = null;
        this.newBalance = -1.0;
    }

    public OfflinePlayer getPlayer() {
        return player;
    }

    public String getPlayerName() {
        return playerName;
    }

    public OfflinePlayer getTargetPlayer() {
        return targetPlayer;
    }

    public String getTargetName() {
        return targetName;
    }

    public double getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public TransactionType getType() {
        return type;
    }

    public String getCallerPlugin() {
        return callerPlugin;
    }

    public String getReason() {
        return reason;
    }

    public double getNewBalance() {
        return newBalance;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
