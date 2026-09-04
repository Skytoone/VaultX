package net.milkbowl.vault.model;

import java.util.UUID;

public class LoanRecord {
    public final String id;
    public final UUID borrower;
    public final String bankName;
    public final double principal;
    public final double remaining;
    public final double interestRate;
    public final long nextBilling;
    public final String status;

    public LoanRecord(String id, UUID borrower, String bankName, double principal, double remaining, double interestRate, long nextBilling, String status) {
        this.id = id;
        this.borrower = borrower;
        this.bankName = bankName;
        this.principal = principal;
        this.remaining = remaining;
        this.interestRate = interestRate;
        this.nextBilling = nextBilling;
        this.status = status;
    }
}
