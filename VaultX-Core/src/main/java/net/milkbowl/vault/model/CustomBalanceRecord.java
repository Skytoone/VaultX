package net.milkbowl.vault.model;

import java.util.UUID;

public class CustomBalanceRecord {
    public final UUID uuid;
    public final String currency;
    public final double balance;

    public CustomBalanceRecord(UUID uuid, String currency, double balance) {
        this.uuid = uuid;
        this.currency = currency;
        this.balance = balance;
    }
}
