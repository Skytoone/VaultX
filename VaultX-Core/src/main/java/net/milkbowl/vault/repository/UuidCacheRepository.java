package net.milkbowl.vault.repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class UuidCacheRepository {

    private final DataSource dataSource;

    public UuidCacheRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String getNameFromCache(UUID uuid) {
        if (dataSource == null || uuid == null) return null;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT player_name FROM vaultx_uuid_cache WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("player_name");
                }
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public UUID getUuidFromCache(String name) {
        if (dataSource == null || name == null) return null;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM vaultx_uuid_cache WHERE player_name_lower = ?")) {
            ps.setString(1, name.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try {
                        return UUID.fromString(rs.getString("uuid"));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public void saveUuidCache(String name, UUID uuid) {
        if (dataSource == null || name == null || uuid == null) return;
        String sql = "INSERT INTO vaultx_uuid_cache (uuid, player_name, player_name_lower) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), player_name_lower = VALUES(player_name_lower)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, name.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }
}
