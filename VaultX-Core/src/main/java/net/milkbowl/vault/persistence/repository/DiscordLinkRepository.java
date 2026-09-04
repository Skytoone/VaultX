package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.persistence.DatabaseConnectionManager;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Repository handling Discord account link persistence.
 */
public class DiscordLinkRepository {

    private final DatabaseConnectionManager dbManager;
    private final Plugin plugin;

    public DiscordLinkRepository(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
        this.plugin = dbManager.getPlugin();
    }

    public void saveDiscordLink(UUID uuid, String discordTag) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query = dbManager.isMySQL()
                    ? "INSERT INTO discord_accounts (uuid, discord_tag) VALUES (?, ?) ON DUPLICATE KEY UPDATE discord_tag = VALUES(discord_tag)"
                    : "INSERT INTO discord_accounts (uuid, discord_tag) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET discord_tag = excluded.discord_tag";
            dbManager.executeDatabaseOperation(conn -> {
                try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                    pstmt.setString(1, uuid.toString());
                    pstmt.setString(2, discordTag);
                    pstmt.executeUpdate();
                }
            }, "Failed to save Discord account link");
        });
    }

    public void removeDiscordLink(UUID uuid) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query = "DELETE FROM discord_accounts WHERE uuid = ?";
            dbManager.executeDatabaseOperation(conn -> {
                try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                    pstmt.setString(1, uuid.toString());
                    pstmt.executeUpdate();
                }
            }, "Failed to remove Discord account link");
        });
    }

    public Map<UUID, String> loadAllDiscordLinks() {
        String query = "SELECT uuid, discord_tag FROM discord_accounts";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<UUID, String> map = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        map.put(UUID.fromString(rs.getString("uuid")), rs.getString("discord_tag"));
                    } catch (Exception ignored) {}
                }
            }
            return map;
        }, new HashMap<>(), "Failed to load Discord account links");
    }
}
