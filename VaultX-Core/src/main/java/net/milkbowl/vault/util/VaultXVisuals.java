package net.milkbowl.vault.util;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class VaultXVisuals {

    private static final Sound SUCCESS_SOUND = getSound("ENTITY_EXPERIENCE_ORB_PICKUP", "ORB_PICKUP");
    private static final Sound FAILURE_SOUND = getSound("ENTITY_VILLAGER_NO", "VILLAGER_NO", "BLOCK_NOTE_BLOCK_BASS", "NOTE_BASS");

    private static Sound getSound(String... names) {
        for (String name : names) {
            try {
                return Sound.valueOf(name);
            } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }

    private static FileConfiguration getConfig() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Vault");
        return plugin != null ? plugin.getConfig() : null;
    }

    /**
     * Plays a transaction success sound.
     */
    public static void playSuccessSound(Player player) {
        FileConfiguration config = getConfig();
        if (config != null && !config.getBoolean("visuals.sounds.enabled", true)) {
            return;
        }
        float volume = config != null ? (float) config.getDouble("visuals.sounds.volume", 0.8) : 0.8f;
        if (player != null && SUCCESS_SOUND != null) {
            player.playSound(player.getLocation(), SUCCESS_SOUND, volume, 1.2f);
        }
    }

    /**
     * Plays a transaction failure/withdrawn sound.
     */
    public static void playFailureSound(Player player) {
        FileConfiguration config = getConfig();
        if (config != null && !config.getBoolean("visuals.sounds.enabled", true)) {
            return;
        }
        float volume = config != null ? (float) config.getDouble("visuals.sounds.volume", 0.8) : 0.8f;
        if (player != null && FAILURE_SOUND != null) {
            player.playSound(player.getLocation(), FAILURE_SOUND, volume, 1.0f);
        }
    }

    /**
     * Sends a color actionbar notification for gain/loss.
     * Example: + 1,500$ or - 50 Gems
     */
    public static void sendTransactionNotification(Player player, String currency, double amount, boolean isDeposit) {
        if (player == null) return;

        if (isDeposit) {
            playSuccessSound(player);
        } else {
            playFailureSound(player);
        }

        FileConfiguration config = getConfig();
        if (config != null && !config.getBoolean("visuals.actionbar.enabled", true)) {
            return;
        }

        String formattedAmount = String.format("%,.2f", amount);
        if (amount == (long) amount) {
            formattedAmount = String.format("%,d", (long) amount);
        }

        String curName = (currency == null || currency.equalsIgnoreCase("default")) ? "$" : " " + currency.toUpperCase();
        String message;

        if (isDeposit) {
            message = "§a§l+ " + formattedAmount + curName;
        } else {
            message = "§c§l- " + formattedAmount + curName;
        }

        try {
            Class<?> chatMessageTypeEnum = Class.forName("net.md_5.bungee.api.ChatMessageType");
            Object actionBarType = chatMessageTypeEnum.getField("ACTION_BAR").get(null);
            
            Class<?> baseComponentClass = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            java.lang.reflect.Method sendMsgMethod = player.spigot().getClass().getMethod("sendMessage", chatMessageTypeEnum, java.lang.reflect.Array.newInstance(baseComponentClass, 0).getClass());
            
            Object textComponent = Class.forName("net.md_5.bungee.api.chat.TextComponent").getConstructor(String.class).newInstance(message);
            Object array = java.lang.reflect.Array.newInstance(baseComponentClass, 1);
            java.lang.reflect.Array.set(array, 0, textComponent);
            
            sendMsgMethod.invoke(player.spigot(), actionBarType, array);
        } catch (Throwable t) {
            try {
                String nmsVersion = org.bukkit.Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
                Class<?> chatComponentTextClass = Class.forName("net.minecraft.server." + nmsVersion + ".ChatComponentText");
                Class<?> iChatBaseComponentClass = Class.forName("net.minecraft.server." + nmsVersion + ".IChatBaseComponent");
                Class<?> packetPlayOutChatClass = Class.forName("net.minecraft.server." + nmsVersion + ".PacketPlayOutChat");
                
                Object chatComponent = chatComponentTextClass.getConstructor(String.class).newInstance(message);
                Object packet = packetPlayOutChatClass.getConstructor(iChatBaseComponentClass, byte.class).newInstance(chatComponent, (byte) 2);
                
                Object nmsPlayer = player.getClass().getMethod("getHandle").invoke(player);
                Object playerConnection = nmsPlayer.getClass().getField("playerConnection").get(nmsPlayer);
                playerConnection.getClass().getMethod("sendPacket", Class.forName("net.minecraft.server." + nmsVersion + ".Packet")).invoke(playerConnection, packet);
            } catch (Throwable t2) {
                player.sendMessage(message);
            }
        }
    }
}
