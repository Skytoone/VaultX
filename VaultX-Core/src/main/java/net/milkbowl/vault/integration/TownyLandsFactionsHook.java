package net.milkbowl.vault.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class TownyLandsFactionsHook {

    private final Plugin plugin;
    private boolean townyEnabled = false;
    private boolean landsEnabled = false;
    private boolean factionsEnabled = false;

    public TownyLandsFactionsHook(Plugin plugin) {
        this.plugin = plugin;
        detectPlugins();
    }

    public void detectPlugins() {
        if (!plugin.getConfig().getBoolean("towns-lands-factions.enabled", true)) {
            return;
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Towny")) {
            townyEnabled = true;
            plugin.getLogger().info("[TownyLandsFactions] Towny integration enabled!");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Lands")) {
            landsEnabled = true;
            plugin.getLogger().info("[TownyLandsFactions] Lands integration enabled!");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Factions") || Bukkit.getPluginManager().isPluginEnabled("FactionsUUID")) {
            factionsEnabled = true;
            plugin.getLogger().info("[TownyLandsFactions] Factions integration enabled!");
        }
    }

    public boolean isTownyEnabled() {
        return townyEnabled;
    }

    public boolean isLandsEnabled() {
        return landsEnabled;
    }

    public boolean isFactionsEnabled() {
        return factionsEnabled;
    }

    public String getPlayerTerritoryName(Player player) {
        if (player == null) return null;

        if (townyEnabled) {
            try {
                Class<?> apiClass = Class.forName("com.palmergames.bukkit.towny.TownyAPI");
                Object api = apiClass.getMethod("getInstance").invoke(null);
                Object resident = apiClass.getMethod("getResident", Player.class).invoke(api, player);
                if (resident != null) {
                    Boolean hasTown = (Boolean) resident.getClass().getMethod("hasTown").invoke(resident);
                    if (hasTown != null && hasTown) {
                        Object town = resident.getClass().getMethod("getTown").invoke(resident);
                        if (town != null) {
                            return (String) town.getClass().getMethod("getName").invoke(town);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (landsEnabled) {
            try {
                Class<?> apiClass = Class.forName("me.angeschossen.lands.api.LandsIntegration");
                Object api = apiClass.getConstructor(Plugin.class).newInstance(plugin);
                Object landPlayer = apiClass.getMethod("getLandPlayer", java.util.UUID.class).invoke(api, player.getUniqueId());
                if (landPlayer != null) {
                    Object land = landPlayer.getClass().getMethod("getOwningLand").invoke(landPlayer);
                    if (land != null) {
                        return (String) land.getClass().getMethod("getName").invoke(land);
                    }
                }
            } catch (Throwable ignored) {}
        }

        return null;
    }
}
