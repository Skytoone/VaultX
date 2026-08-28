package net.milkbowl.vault.economy.events;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Asynchronous event dispatched when a bank account transaction or management event occurs in VaultX.
 */
public class VaultBankTransactionEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final String bankName;
    private final OfflinePlayer player;
    private final String playerName;
    private final double amount;
    private final BankTransactionType type;
    private final double newBankBalance;

    public enum BankTransactionType {
        DEPOSIT,
        WITHDRAW,
        CREATE_BANK,
        DELETE_BANK
    }

    public VaultBankTransactionEvent(String bankName, OfflinePlayer player, double amount, BankTransactionType type, double newBankBalance) {
        super(true); // Is Asynchronous
        this.bankName = bankName;
        this.player = player;
        this.playerName = player != null ? player.getName() : null;
        this.amount = amount;
        this.type = type;
        this.newBankBalance = newBankBalance;
    }

    public VaultBankTransactionEvent(String bankName, String playerName, double amount, BankTransactionType type, double newBankBalance) {
        super(true); // Is Asynchronous
        this.bankName = bankName;
        this.player = null;
        this.playerName = playerName;
        this.amount = amount;
        this.type = type;
        this.newBankBalance = newBankBalance;
    }

    public String getBankName() {
        return bankName;
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

    public BankTransactionType getType() {
        return type;
    }

    public double getNewBankBalance() {
        return newBankBalance;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
