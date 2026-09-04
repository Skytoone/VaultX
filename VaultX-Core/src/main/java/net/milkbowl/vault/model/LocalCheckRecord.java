package net.milkbowl.vault.model;

import java.util.UUID;

public class LocalCheckRecord {
    public final String id;
    public final double amount;
    public final String currency;
    public final UUID creatorUuid;
    public final String status;
    public final long createdAt;

    public LocalCheckRecord(String id, double amount, String currency, UUID creatorUuid, String status, long createdAt) {
        this.id = id;
        this.amount = amount;
        this.currency = currency;
        this.creatorUuid = creatorUuid;
        this.status = status;
        this.createdAt = createdAt;
    }

    public LocalCheckRecord(String id, double amount, String currency, UUID creatorUuid, String status) {
        this(id, amount, currency, creatorUuid, status, 0L);
    }
}
