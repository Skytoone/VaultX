package net.milkbowl.vault.model;

public class PlayerTransactionRecord {
    public final long timestamp;
    public final String uuid;
    public final String type;
    public final String currency;
    public final double amount;
    public final String otherParty;
    public final String category;

    public PlayerTransactionRecord(long timestamp, String uuid, String type, String currency, double amount, String otherParty, String category) {
        this.timestamp = timestamp;
        this.uuid = uuid;
        this.type = type;
        this.currency = currency;
        this.amount = amount;
        this.otherParty = otherParty;
        this.category = category == null ? "OTHER" : category;
    }
}
