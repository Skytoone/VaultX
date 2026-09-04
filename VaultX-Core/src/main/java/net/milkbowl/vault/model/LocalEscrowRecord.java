package net.milkbowl.vault.model;

public class LocalEscrowRecord {
    public final String id;
    public final String sender;
    public final String receiver;
    public final double amount;
    public final String currency;
    public final String status;
    public final long timeoutAt;

    public LocalEscrowRecord(String id, String sender, String receiver, double amount, String currency, String status, long timeoutAt) {
        this.id = id;
        this.sender = sender;
        this.receiver = receiver;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.timeoutAt = timeoutAt;
    }
}
