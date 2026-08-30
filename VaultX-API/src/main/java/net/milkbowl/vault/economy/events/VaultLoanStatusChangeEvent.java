package net.milkbowl.vault.economy.events;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Asynchronous event dispatched when a loan's status changes (e.g., TAKEN, REPAID, OVERDUE, DEFAULTED).
 */
public class VaultLoanStatusChangeEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final OfflinePlayer player;
    private final String loanId;
    private final double amount;
    private final String currency;
    private final LoanStatus status;

    public enum LoanStatus {
        TAKEN,
        REPAID,
        OVERDUE,
        DEFAULTED
    }

    public VaultLoanStatusChangeEvent(OfflinePlayer player, String loanId, double amount, String currency, LoanStatus status) {
        super(true); // Asynchronous
        this.player = player;
        this.loanId = loanId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
    }

    public OfflinePlayer getPlayer() {
        return player;
    }

    public String getLoanId() {
        return loanId;
    }

    public double getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public LoanStatus getStatus() {
        return status;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
