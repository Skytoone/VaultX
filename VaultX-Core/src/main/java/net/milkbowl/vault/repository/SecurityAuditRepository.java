package net.milkbowl.vault.repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SecurityAuditRepository {

    public static class PendingWebhookRecord {
        public final long id;
        public final String payload;
        public final int attempts;
        public final long nextRetryTime;

        public PendingWebhookRecord(long id, String payload, int attempts, long nextRetryTime) {
            this.id = id;
            this.payload = payload;
            this.attempts = attempts;
            this.nextRetryTime = nextRetryTime;
        }
    }

    private final DataSource dataSource;

    public SecurityAuditRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void saveSecurityAudit(UUID playerUuid, String playerName, double amount, String action, String details) {
        if (dataSource == null) return;
        String sql = "INSERT INTO vaultx_security_audit (player_uuid, player_name, amount, action, details, timestamp) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid != null ? playerUuid.toString() : null);
            ps.setString(2, playerName);
            ps.setDouble(3, amount);
            ps.setString(4, action);
            ps.setString(5, details);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public void savePendingWebhook(String payload, int attempts, long nextRetryTime) {
        if (dataSource == null || payload == null) return;
        String sql = "INSERT INTO vaultx_pending_webhooks (payload, attempts, next_retry_time) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, payload);
            ps.setInt(2, attempts);
            ps.setLong(3, nextRetryTime);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public List<PendingWebhookRecord> getPendingWebhooks(long currentTime) {
        List<PendingWebhookRecord> result = new ArrayList<>();
        if (dataSource == null) return result;
        String sql = "SELECT id, payload, attempts, next_retry_time FROM vaultx_pending_webhooks WHERE next_retry_time <= ? LIMIT 50";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, currentTime);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new PendingWebhookRecord(
                            rs.getLong("id"),
                            rs.getString("payload"),
                            rs.getInt("attempts"),
                            rs.getLong("next_retry_time")
                    ));
                }
            }
        } catch (SQLException ignored) {}
        return result;
    }

    public void deletePendingWebhook(long id) {
        if (dataSource == null) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM vaultx_pending_webhooks WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public void updatePendingWebhook(long id, int attempts, long nextRetryTime) {
        if (dataSource == null) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE vaultx_pending_webhooks SET attempts = ?, next_retry_time = ? WHERE id = ?")) {
            ps.setInt(1, attempts);
            ps.setLong(2, nextRetryTime);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }
}
