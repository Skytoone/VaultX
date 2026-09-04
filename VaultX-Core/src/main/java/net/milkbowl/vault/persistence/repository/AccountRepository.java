package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.economy.VaultSnapshotAPI.EconomySnapshot;
import net.milkbowl.vault.persistence.DatabaseConnectionManager;
import net.milkbowl.vault.redis.LocalFailoverManager.CustomBalanceRecord;
import net.milkbowl.vault.redis.VaultRedisManager;
import net.milkbowl.vault.util.UUIDCache;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AccountRepository {

    private final DatabaseConnectionManager dbManager;
    private final Plugin plugin;
    private final SnapshotRepository snapshotRepository;
    private final DiscordLinkRepository discordLinkRepository;
    private final Map<String, CachedLeaderboard> localLeaderboardCache = new ConcurrentHashMap<>();

    private static class CachedLeaderboard {
        final long timestamp;
        final List<VaultRedisManager.LeaderboardEntry> entries;
        CachedLeaderboard(long timestamp, List<VaultRedisManager.LeaderboardEntry> entries) {
            this.timestamp = timestamp;
            this.entries = entries;
        }
    }

    public AccountRepository(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
        this.plugin = dbManager.getPlugin();
        this.snapshotRepository = new SnapshotRepository(dbManager);
        this.discordLinkRepository = new DiscordLinkRepository(dbManager);
    }

    public SnapshotRepository getSnapshotRepository() {
        return snapshotRepository;
    }

    public DiscordLinkRepository getDiscordLinkRepository() {
        return discordLinkRepository;
    }

    public void saveCustomCurrencyBalance(UUID uuid, String currency, double balance) {
        saveCustomCurrencyBalance(uuid, currency, balance, System.currentTimeMillis());
    }

    public void saveCustomCurrencyBalance(UUID uuid, String currency, double balance, long timestamp) {
        String query = dbManager.isMySQL()
                ? "INSERT INTO custom_currency_balances (uuid, currency, balance, last_updated) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE balance = VALUES(balance), last_updated = VALUES(last_updated)"
                : "INSERT INTO custom_currency_balances (uuid, currency, balance, last_updated) VALUES (?, ?, ?, ?) ON CONFLICT(uuid, currency) DO UPDATE SET balance = excluded.balance, last_updated = excluded.last_updated";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, currency.toLowerCase());
                pstmt.setDouble(3, balance);
                pstmt.setLong(4, timestamp);
                pstmt.executeUpdate();
            }
        }, "Failed to save custom currency balance locally");
    }

    public long getCustomCurrencyTimestamp(UUID uuid, String currency) {
        String query = "SELECT last_updated FROM custom_currency_balances WHERE uuid = ? AND currency = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, currency.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("last_updated");
                    }
                }
            }
            return 0L;
        }, 0L, "Failed to fetch custom currency timestamp");
    }

    public double getCustomCurrencyBalance(UUID uuid, String currency) {
        String query = "SELECT balance FROM custom_currency_balances WHERE uuid = ? AND currency = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, currency.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("balance");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to fetch custom currency balance");
    }

    public void saveUuidCache(String name, UUID uuid) {
        if (name == null || uuid == null) return;
        net.milkbowl.vault.util.FoliaScheduler.runAsync(plugin, () -> {
            String query = dbManager.isMySQL()
                    ? "INSERT INTO local_uuid_cache (name, uuid, last_updated) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE uuid = VALUES(uuid), last_updated = VALUES(last_updated)"
                    : "INSERT INTO local_uuid_cache (name, uuid, last_updated) VALUES (?, ?, ?) ON CONFLICT(name) DO UPDATE SET uuid = excluded.uuid, last_updated = excluded.last_updated";
            dbManager.executeDatabaseOperation(conn -> {
                try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                    pstmt.setString(1, name.toLowerCase());
                    pstmt.setString(2, uuid.toString());
                    pstmt.setLong(3, System.currentTimeMillis());
                    pstmt.executeUpdate();
                }
            }, "Failed to save UUID cache locally");
        });

        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            redis.saveUuidToRedis(name, uuid);
        }
    }

    public UUID getUuidFromCache(String name) {
        if (name == null) return null;
        String query = "SELECT uuid FROM local_uuid_cache WHERE name = ?";
        String uuidStr = dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, name.toLowerCase());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("uuid");
                    }
                }
            }
            return null;
        }, null, "Failed to query local UUID cache");

        if (uuidStr != null) {
            try {
                return UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    public String getNameFromCache(UUID uuid) {
        if (uuid == null) return null;
        String query = "SELECT name FROM local_uuid_cache WHERE uuid = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("name");
                    }
                }
            }
            return null;
        }, null, "Failed to query local name cache");
    }

    public double getTotalMoneySupply(String currency) {
        String query = "SELECT SUM(balance) FROM user_balances WHERE currency = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, currency != null ? currency.toLowerCase() : "default");
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) return rs.getDouble(1);
                }
            }
            return 0.0;
        }, 0.0, "Failed to get total money supply");
    }

    public double getAverageAccountBalance(String currency) {
        String query = "SELECT AVG(balance) FROM user_balances WHERE currency = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, currency != null ? currency.toLowerCase() : "default");
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) return rs.getDouble(1);
                }
            }
            return 0.0;
        }, 0.0, "Failed to get average account balance");
    }

    public double getTotalCurrencyBalanceSum(String currency) {
        String key = currency == null ? "default" : currency.toLowerCase();
        String query = "SELECT SUM(balance) as total FROM custom_currency_balances WHERE LOWER(currency) = ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, key);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("total");
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to calculate " + key + " total money supply sum");
    }

    public void applyBulkInterest(String currency, double factor, long timestamp) {
        String query = "UPDATE custom_currency_balances SET balance = balance * ?, last_updated = ? WHERE currency = ? AND balance > 0";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setDouble(1, factor);
                pstmt.setLong(2, timestamp);
                pstmt.setString(3, currency.toLowerCase());
                pstmt.executeUpdate();
            }
        }, "Failed to apply bulk interest to custom currencies");
    }

    public void applyBulkWealthTax(double threshold, double taxRate, long timestamp) {
        String query = "UPDATE custom_currency_balances SET balance = balance - ((balance - ?) * ?), last_updated = ? WHERE currency != 'default' AND balance > ?";
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setDouble(1, threshold);
                pstmt.setDouble(2, taxRate);
                pstmt.setLong(3, timestamp);
                pstmt.setDouble(4, threshold);
                pstmt.executeUpdate();
            }
        }, "Failed to apply bulk wealth tax");
    }

    public List<CustomBalanceRecord> getAllCustomBalances() {
        String query = "SELECT uuid, currency, balance FROM custom_currency_balances";
        return dbManager.executeDatabaseQuery(conn -> {
            List<CustomBalanceRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new CustomBalanceRecord(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("currency").toLowerCase(),
                            rs.getDouble("balance")
                    ));
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get all custom balances");
    }

    public List<CustomBalanceRecord> getCustomBalancesUpdatedSince(long timestamp) {
        String query = "SELECT uuid, currency, balance FROM custom_currency_balances WHERE last_updated >= ?";
        return dbManager.executeDatabaseQuery(conn -> {
            List<CustomBalanceRecord> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setLong(1, timestamp);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new CustomBalanceRecord(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("currency").toLowerCase(),
                                rs.getDouble("balance")
                        ));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get custom balances updated since " + timestamp);
    }

    public double getCustomWealthTaxSum(String currency, double threshold, double taxRate) {
        String query = "SELECT SUM((balance - ?) * ?) FROM custom_currency_balances WHERE currency = ? AND balance > ?";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setDouble(1, threshold);
                pstmt.setDouble(2, taxRate);
                pstmt.setString(3, currency.toLowerCase());
                pstmt.setDouble(4, threshold);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble(1);
                    }
                }
            }
            return 0.0;
        }, 0.0, "Failed to calculate custom wealth tax sum");
    }

    public List<VaultRedisManager.LeaderboardEntry> getLocalLeaderboard(String currency, int limit) {
        String cacheKey = (currency == null ? "default" : currency.toLowerCase()) + ":" + limit;
        long now = System.currentTimeMillis();
        CachedLeaderboard cached = localLeaderboardCache.get(cacheKey);
        if (cached != null && (now - cached.timestamp) < 10000L) {
            return cached.entries;
        }
        String query = "SELECT c.uuid, c.balance, u.name FROM custom_currency_balances c LEFT JOIN local_uuid_cache u ON c.uuid = u.uuid WHERE c.currency = ? AND c.balance > 0 ORDER BY c.balance DESC LIMIT ?";
        List<VaultRedisManager.LeaderboardEntry> result = dbManager.executeDatabaseQuery(conn -> {
            List<VaultRedisManager.LeaderboardEntry> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, (currency == null ? "default" : currency).toLowerCase());
                pstmt.setInt(2, limit);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        double balance = rs.getDouble("balance");
                        String name = rs.getString("name");
                        if (name == null || name.isEmpty()) {
                            name = UUIDCache.getName(uuid);
                        }
                        if (name == null || name.isEmpty()) {
                            name = "Unknown";
                        }
                        list.add(new VaultRedisManager.LeaderboardEntry(name, balance));
                    }
                }
            }
            return list;
        }, new ArrayList<>(), "Failed to get local leaderboard");
        localLeaderboardCache.put(cacheKey, new CachedLeaderboard(now, result));
        return result;
    }

    public void saveDiscordLink(UUID uuid, String discordTag) {
        discordLinkRepository.saveDiscordLink(uuid, discordTag);
    }

    public void removeDiscordLink(UUID uuid) {
        discordLinkRepository.removeDiscordLink(uuid);
    }

    public Map<UUID, String> loadAllDiscordLinks() {
        return discordLinkRepository.loadAllDiscordLinks();
    }

    public Map<UUID, Double> getTopBalances(String currency, int limit) {
        String query = "SELECT uuid, balance FROM player_balances WHERE LOWER(currency) = LOWER(?) ORDER BY balance DESC LIMIT ?";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<UUID, Double> map = new LinkedHashMap<>();
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, currency == null ? "default" : currency);
                pstmt.setInt(2, limit > 0 ? limit : 10);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        try {
                            map.put(UUID.fromString(rs.getString("uuid")), rs.getDouble("balance"));
                        } catch (Exception ignored) {}
                    }
                }
            }
            return map;
        }, new LinkedHashMap<>(), "Failed to get top balances");
    }

    public int getPlayerRank(UUID uuid, String currency) {
        if (uuid == null) return -1;
        String query = "SELECT COUNT(*) + 1 AS rank FROM player_balances WHERE LOWER(currency) = LOWER(?) AND balance > (SELECT balance FROM player_balances WHERE uuid = ? AND LOWER(currency) = LOWER(?))";
        return dbManager.executeDatabaseQuery(conn -> {
            try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                pstmt.setString(1, currency == null ? "default" : currency);
                pstmt.setString(2, uuid.toString());
                pstmt.setString(3, currency == null ? "default" : currency);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("rank");
                    }
                }
            }
            return -1;
        }, -1, "Failed to get player rank");
    }

    public boolean createSnapshot(String snapshotId, String label, long timestamp, int totalAccounts, double totalNetWorth, Map<UUID, Map<String, Double>> allBalances) {
        return snapshotRepository.createSnapshot(snapshotId, label, timestamp, totalAccounts, totalNetWorth, allBalances);
    }

    public List<EconomySnapshot> getSnapshotsFromDb(int limit) {
        return snapshotRepository.getSnapshotsFromDb(limit);
    }

    public Map<UUID, Map<String, Double>> getSnapshotBalances(String snapshotId) {
        return snapshotRepository.getSnapshotBalances(snapshotId);
    }

    public Map<String, Double> getPlayerSnapshotBalances(UUID uuid, String snapshotId) {
        return snapshotRepository.getPlayerSnapshotBalances(uuid, snapshotId);
    }

    public boolean deleteSnapshotFromDb(String snapshotId) {
        return snapshotRepository.deleteSnapshotFromDb(snapshotId);
    }
}
