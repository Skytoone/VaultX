package net.milkbowl.vault.model;

import java.util.UUID;

public class SubscriptionRecord {
    public final String id;
    public final UUID subscriber;
    public final String targetType;
    public final String target;
    public final double amount;
    public final String currency;
    public final int intervalHours;
    public final long lastBilling;
    public final long nextBilling;
    public final String status;
    public final long createdAt;

    public SubscriptionRecord(String id, UUID subscriber, String targetType, String target, double amount, String currency, int intervalHours, long lastBilling, long nextBilling, String status, long createdAt) {
        this.id = id;
        this.subscriber = subscriber;
        this.targetType = targetType;
        this.target = target;
        this.amount = amount;
        this.currency = currency;
        this.intervalHours = intervalHours;
        this.lastBilling = lastBilling;
        this.nextBilling = nextBilling;
        this.status = status;
        this.createdAt = createdAt;
    }
}
