package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.economy.VaultStakingAPI;
import net.milkbowl.vault.persistence.DatabaseConnectionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StakingRepository {

    private final DatabaseConnectionManager dbManager;

    public StakingRepository(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
    }

    public void saveStake(VaultStakingAPI.StakeDeposit deposit) {
        if (deposit == null) return;
        String sql = dbManager.isPostgreSQL() ?
                "INSERT INTO local_stakes (deposit_id, player_uuid, currency, principal, interest_rate, staked_at_ms, lock_period_ms, is_matured, is_claimed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (deposit_id) DO UPDATE SET is_matured = EXCLUDED.is_matured, is_claimed = EXCLUDED.is_claimed" :
                (dbManager.isMySQL() ? "INSERT INTO local_stakes (deposit_id, player_uuid, currency, principal, interest_rate, staked_at_ms, lock_period_ms, is_matured, is_claimed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE is_matured = VALUES(is_matured), is_claimed = VALUES(is_claimed)" :
                        "REPLACE INTO local_stakes (deposit_id, player_uuid, currency, principal, interest_rate, staked_at_ms, lock_period_ms, is_matured, is_claimed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
        dbManager.executeDatabaseOperation(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, deposit.depositId());
                ps.setString(2, deposit.playerUuid().toString());
                ps.setString(3, deposit.currency());
                ps.setDouble(4, deposit.principal());
                ps.setDouble(5, deposit.interestRate());
                ps.setLong(6, deposit.stakedAtMs());
                ps.setLong(7, deposit.lockPeriodMs());
                ps.setInt(8, deposit.isMatured() ? 1 : 0);
                ps.setInt(9, deposit.isClaimed() ? 1 : 0);
                ps.executeUpdate();
            }
        }, "Failed to save stake deposit");
    }

    public Map<String, VaultStakingAPI.StakeDeposit> loadAllStakes() {
        String query = "SELECT deposit_id, player_uuid, currency, principal, interest_rate, staked_at_ms, lock_period_ms, is_matured, is_claimed FROM local_stakes WHERE is_claimed = 0";
        return dbManager.executeDatabaseQuery(conn -> {
            Map<String, VaultStakingAPI.StakeDeposit> result = new HashMap<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    try {
                        String id = rs.getString("deposit_id");
                        UUID player = UUID.fromString(rs.getString("player_uuid"));
                        String curr = rs.getString("currency");
                        double principal = rs.getDouble("principal");
                        double rate = rs.getDouble("interest_rate");
                        long stakedAt = rs.getLong("staked_at_ms");
                        long lockPeriod = rs.getLong("lock_period_ms");
                        boolean matured = rs.getInt("is_matured") == 1;
                        boolean claimed = rs.getInt("is_claimed") == 1;

                        result.put(id, new VaultStakingAPI.StakeDeposit(
                                id, player, curr, principal, rate, stakedAt, lockPeriod, matured, claimed
                        ));
                    } catch (Exception ignored) {}
                }
            }
            return result;
        }, new HashMap<>(), "Failed to load active stakes");
    }
}
