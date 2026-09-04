package net.milkbowl.vault.security;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

public class FirewallWebhookNotifier {

    private final DiscordWebhookNotifier discordNotifier;

    public FirewallWebhookNotifier(Plugin plugin) {
        this.discordNotifier = new DiscordWebhookNotifier(plugin);
    }

    public void loadConfig() {
        if (discordNotifier != null) {
            discordNotifier.loadConfig();
        }
    }

    public void sendAlertAsync(String action, OfflinePlayer player, String details, int color) {
        if (discordNotifier != null) {
            discordNotifier.sendAlertAsync(action, player, details, color);
        }
    }

    public void close() {
        if (discordNotifier != null) {
            discordNotifier.close();
        }
    }

    public DiscordWebhookNotifier getDiscordNotifier() {
        return discordNotifier;
    }
}
