package net.milkbowl.vault.util;

import net.milkbowl.vault.Vault;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Scanner;
import java.util.function.Consumer;

public class UpdateChecker {

    private final Vault plugin;
    private final int resourceId;

    public UpdateChecker(Vault plugin, int resourceId) {
        this.plugin = plugin;
        this.resourceId = resourceId;
    }

    public void getVersion(final Consumer<String> consumer) {
        net.milkbowl.vault.util.FoliaScheduler.runAsync(this.plugin, () -> {
            try (InputStream inputStream = URI
                    .create("https://api.spigotmc.org/legacy/update.php?resource=" + this.resourceId).toURL()
                    .openStream();
                    Scanner scanner = new Scanner(inputStream)) {
                if (scanner.hasNext()) {
                    consumer.accept(scanner.next());
                }
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not check for updates: " + exception.getMessage());
            }
        });
    }
}
