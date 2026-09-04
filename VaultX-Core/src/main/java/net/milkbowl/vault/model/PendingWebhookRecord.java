package net.milkbowl.vault.model;

public class PendingWebhookRecord {
    public final long id;
    public final String payload;
    public final int attempts;
    public final long nextRetry;

    public PendingWebhookRecord(long id, String payload, int attempts, long nextRetry) {
        this.id = id;
        this.payload = payload;
        this.attempts = attempts;
        this.nextRetry = nextRetry;
    }
}
