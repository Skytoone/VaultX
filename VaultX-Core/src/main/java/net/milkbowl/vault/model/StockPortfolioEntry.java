package net.milkbowl.vault.model;

public class StockPortfolioEntry {
    public final String commodity;
    public final double shares;

    public StockPortfolioEntry(String commodity, double shares) {
        this.commodity = commodity;
        this.shares = shares;
    }
}
