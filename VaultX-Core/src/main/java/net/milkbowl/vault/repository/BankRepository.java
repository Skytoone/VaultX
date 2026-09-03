package net.milkbowl.vault.repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BankRepository {

    private final DataSource dataSource;

    public BankRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Map<String, Double> loadAllBanks() {
        Map<String, Double> result = new HashMap<>();
        if (dataSource == null) return result;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT bank_name, balance FROM vaultx_banks");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString("bank_name").toLowerCase(), rs.getDouble("balance"));
            }
        } catch (SQLException ignored) {}
        return result;
    }

    public double getBankBalance(String bankName) {
        if (dataSource == null || bankName == null) return 0.0;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT balance FROM vaultx_banks WHERE bank_name = ?")) {
            ps.setString(1, bankName.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("balance");
                }
            }
        } catch (SQLException ignored) {}
        return 0.0;
    }

    public void saveBankBalance(String bankName, double balance) {
        if (dataSource == null || bankName == null) return;
        String sql = "INSERT INTO vaultx_banks (bank_name, balance) VALUES (?, ?) ON DUPLICATE KEY UPDATE balance = VALUES(balance)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, bankName.toLowerCase());
            ps.setDouble(2, balance);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public void deleteBankAccount(String bankName) {
        if (dataSource == null || bankName == null) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM vaultx_banks WHERE bank_name = ?")) {
            ps.setString(1, bankName.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }
}
