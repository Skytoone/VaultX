package net.milkbowl.vault.economy.events;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Synchronous cancellable event dispatched BEFORE an economy transaction takes place.
 * External plugins can cancel this event to block transactions (e.g., combat loggers, anti-cheats, account freezes).
 */
public class VaultPreTransactionEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();

    private final OfflinePlayer player;
    private final String playerName;
    private final OfflinePlayer targetPlayer;
    private final String targetName;
    private final double amount;
    private final String currency;
    private final TransactionType type;
    private final String callerPlugin;
    private boolean cancelled = false;
    private String cancelReason = "Transaction cancelled by external plugin";

    public enum TransactionType {
        DEPOSIT,
        WITHDRAW
    }

    public VaultPreTransactionEvent(OfflinePlayer player, OfflinePlayer targetPlayer, double amount, String currency, TransactionType type, String callerPlugin) {
        super(false); // Synchronous
        this.player = player;
        this.playerName = player != null ? player.getName() : null;
        this.targetPlayer = targetPlayer;
        this.targetName = targetPlayer != null ? targetPlayer.getName() : null;
        this.amount = amount;
        this.currency = currency != null ? currency : "default";
        this.type = type;
        this.callerPlugin = callerPlugin != null ? callerPlugin : "VaultX";
    }

    public VaultPreTransactionEvent(OfflinePlayer player, double amount, String currency, TransactionType type, String callerPlugin) {
        this(player, null, amount, currency, type, callerPlugin);
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

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
