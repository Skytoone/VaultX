package net.milkbowl.vault.model;

import java.util.UUID;

public class BankShareholderRecord {
    public final UUID uuid;
    public final String bankName;
    public final double shares;

    public BankShareholderRecord(UUID uuid, String bankName, double shares) {
        this.uuid = uuid;
        this.bankName = bankName;
        this.shares = shares;
    }
}
