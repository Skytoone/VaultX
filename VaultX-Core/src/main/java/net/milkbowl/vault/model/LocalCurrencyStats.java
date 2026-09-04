package net.milkbowl.vault.model;

public class LocalCurrencyStats {
    public final double totalMoney;
    public final int accountsCount;
    public final double averageBalance;
    public final double transactionVolume24h;

    public LocalCurrencyStats(double totalMoney, int accountsCount, double averageBalance, double transactionVolume24h) {
        this.totalMoney = totalMoney;
        this.accountsCount = accountsCount;
        this.averageBalance = averageBalance;
        this.transactionVolume24h = transactionVolume24h;
    }

    public LocalCurrencyStats(double totalMoney, double averageBalance, double transactionVolume24h) {
        this(totalMoney, 0, averageBalance, transactionVolume24h);
    }
}
