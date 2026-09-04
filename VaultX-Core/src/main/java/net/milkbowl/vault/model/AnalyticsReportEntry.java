package net.milkbowl.vault.model;

public class AnalyticsReportEntry {
    public final String currency;
    public final String category;
    public final double created;
    public final double destroyed;

    public AnalyticsReportEntry(String currency, String category, double created, double destroyed) {
        this.currency = currency;
        this.category = category;
        this.created = created;
        this.destroyed = destroyed;
    }
}
