package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.redis.VaultRedisManager;

import java.util.UUID;

public class UUIDCacheRepository {

    public static void saveUuidCache(String name, UUID uuid) {
        LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm != null) {
            fm.saveUuidCache(name, uuid);
        }
    }

    public static String getNameFromDbOrRedis(UUID uuid) {
        LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm != null) {
            String dbName = fm.getNameFromCache(uuid);
            if (dbName != null) {
                return dbName;
            }
        }
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            String dbName = redis.getNameFromRedis(uuid);
            if (dbName != null) {
                if (fm != null) {
                    fm.saveUuidCache(dbName, uuid);
                }
                return dbName;
            }
        }
        return null;
    }

    public static UUID getUuidFromDbOrRedis(String name) {
        LocalFailoverManager fm = Vault.getFailoverManager();
        if (fm != null) {
            UUID dbUuid = fm.getUuidFromCache(name);
            if (dbUuid != null) {
                return dbUuid;
            }
        }
        VaultRedisManager redis = VaultRedisManager.getInstance();
        if (redis != null && redis.isOnline()) {
            UUID dbUuid = redis.getUuidFromRedis(name);
            if (dbUuid != null) {
                if (fm != null) {
                    fm.saveUuidCache(name, dbUuid);
                }
                return dbUuid;
            }
        }
        return null;
    }
}
