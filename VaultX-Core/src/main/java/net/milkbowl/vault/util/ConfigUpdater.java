package net.milkbowl.vault.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ConfigUpdater {

    private static class ParentInfo {
        final int indent;
        final String key;
        ParentInfo(int indent, String key) {
            this.indent = indent;
            this.key = key;
        }
    }

    private static class DefaultKey {
        final String path;
        final int indent;
        final List<String> lines;
        DefaultKey(String path, int indent, List<String> lines) {
            this.path = path;
            this.indent = indent;
            this.lines = lines;
        }
    }

    public static void updateConfig(File configFile, InputStream defaultStream) throws IOException {
        if (!configFile.exists() || defaultStream == null) return;

        YamlConfiguration activeConfig = YamlConfiguration.loadConfiguration(configFile);

        List<String> activeLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                activeLines.add(line);
            }
        }

        List<String> defaultLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(defaultStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                defaultLines.add(line);
            }
        }

        List<DefaultKey> defaultKeys = parseDefaultKeys(defaultLines);
        boolean modified = false;

        for (DefaultKey dk : defaultKeys) {
            if (!activeConfig.contains(dk.path) && activeConfig.get(dk.path) == null) {
                String parentPath = "";
                int parentIndent = 0;
                int lastDot = dk.path.lastIndexOf('.');
                if (lastDot > 0) {
                    parentPath = dk.path.substring(0, lastDot);
                    for (DefaultKey searchDk : defaultKeys) {
                        if (searchDk.path.equalsIgnoreCase(parentPath)) {
                            parentIndent = searchDk.indent;
                            break;
                        }
                    }
                }

                int insertIdx = findInsertionIndex(activeLines, parentPath, parentIndent);
                activeLines.addAll(insertIdx, dk.lines);
                
                // Set placeholder value so contains() returns true if a nested child checks it later in the loop
                activeConfig.set(dk.path, "placeholder");
                modified = true;
            }
        }

        if (modified) {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8))) {
                for (String line : activeLines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
    }

    private static List<DefaultKey> parseDefaultKeys(List<String> defaultLines) {
        List<DefaultKey> keys = new ArrayList<>();
        List<ParentInfo> activeParents = new ArrayList<>();
        List<String> commentBuffer = new ArrayList<>();

        for (String line : defaultLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                commentBuffer.add(line);
                continue;
            }

            String key = getKeyName(line);
            if (key == null) {
                if (trimmed.startsWith("-") && !keys.isEmpty()) {
                    DefaultKey lastKey = keys.get(keys.size() - 1);
                    lastKey.lines.add(line);
                } else {
                    commentBuffer.clear();
                }
                continue;
            }

            int indent = getIndentation(line);
            activeParents.removeIf(p -> p.indent >= indent);

            StringBuilder pathBuilder = new StringBuilder();
            for (ParentInfo p : activeParents) {
                if (pathBuilder.length() > 0) pathBuilder.append(".");
                pathBuilder.append(p.key);
            }
            if (pathBuilder.length() > 0) pathBuilder.append(".");
            pathBuilder.append(key);

            String fullPath = pathBuilder.toString();
            List<String> keyLines = new ArrayList<>(commentBuffer);
            keyLines.add(line);
            commentBuffer.clear();

            keys.add(new DefaultKey(fullPath, indent, keyLines));
            activeParents.add(new ParentInfo(indent, key));
        }
        return keys;
    }

    private static int findInsertionIndex(List<String> activeLines, String parentPath, int parentIndent) {
        if (parentPath.isEmpty()) {
            return activeLines.size();
        }

        List<ParentInfo> activeParents = new ArrayList<>();
        int parentLineIndex = -1;

        for (int i = 0; i < activeLines.size(); i++) {
            String line = activeLines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            String key = getKeyName(line);
            if (key == null) continue;

            int indent = getIndentation(line);
            activeParents.removeIf(p -> p.indent >= indent);

            StringBuilder pathBuilder = new StringBuilder();
            for (ParentInfo p : activeParents) {
                if (pathBuilder.length() > 0) pathBuilder.append(".");
                pathBuilder.append(p.key);
            }
            if (pathBuilder.length() > 0) pathBuilder.append(".");
            pathBuilder.append(key);

            String fullPath = pathBuilder.toString();
            if (fullPath.equalsIgnoreCase(parentPath) && indent == parentIndent) {
                parentLineIndex = i;
                break;
            }
            activeParents.add(new ParentInfo(indent, key));
        }

        if (parentLineIndex == -1) {
            return activeLines.size();
        }

        for (int i = parentLineIndex + 1; i < activeLines.size(); i++) {
            String line = activeLines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int indent = getIndentation(line);
            if (indent <= parentIndent) {
                int insertIdx = i;
                while (insertIdx > parentLineIndex + 1) {
                    String prevLine = activeLines.get(insertIdx - 1);
                    String prevTrimmed = prevLine.trim();
                    if (prevTrimmed.startsWith("#") || prevTrimmed.isEmpty()) {
                        insertIdx--;
                    } else {
                        break;
                    }
                }
                return insertIdx;
            }
        }

        return activeLines.size();
    }

    private static String getKeyName(String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) return null;
        String keyPart = line.substring(0, colon).trim();
        if (keyPart.isEmpty() || keyPart.startsWith("#") || keyPart.startsWith("-")) return null;
        if (keyPart.contains(" ") || keyPart.contains("\"") || keyPart.contains("'")) return null;
        return keyPart;
    }

    private static int getIndentation(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }
}
