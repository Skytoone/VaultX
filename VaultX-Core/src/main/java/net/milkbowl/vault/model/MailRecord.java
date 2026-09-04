package net.milkbowl.vault.model;

import java.util.UUID;

public class MailRecord {
    public final int id;
    public final UUID uuid;
    public final String senderName;
    public final String message;
    public final double amount;
    public final String currency;
    public final String status;
    public final long timestamp;

    public MailRecord(int id, UUID uuid, String senderName, String message, double amount, String currency, String status, long timestamp) {
        this.id = id;
        this.uuid = uuid;
        this.senderName = senderName;
        this.message = message;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.timestamp = timestamp;
    }
}
