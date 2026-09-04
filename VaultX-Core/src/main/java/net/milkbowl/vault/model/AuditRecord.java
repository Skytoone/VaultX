package net.milkbowl.vault.model;

public class AuditRecord {
    public final long timestamp;
    public final String uuid;
    public final String name;
    public final double amount;
    public final String action;
    public final String details;

    public AuditRecord(long timestamp, String uuid, String name, double amount, String action, String details) {
        this.timestamp = timestamp;
        this.uuid = uuid;
        this.name = name;
        this.amount = amount;
        this.action = action;
        this.details = details;
    }
}
