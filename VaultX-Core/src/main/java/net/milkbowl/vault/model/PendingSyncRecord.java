package net.milkbowl.vault.model;

public class PendingSyncRecord {
    public final String uuid;
    public final String currency;
    public final double balance;
    public final long timestamp;

    public PendingSyncRecord(String uuid, String currency, double balance, long timestamp) {
        this.uuid = uuid;
        this.currency = currency;
        this.balance = balance;
        this.timestamp = timestamp;
    }
}
