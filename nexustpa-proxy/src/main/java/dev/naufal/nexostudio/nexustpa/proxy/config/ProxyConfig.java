package dev.naufal.nexostudio.nexustpa.proxy.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and parses the proxy config.yml.
 * Uses SnakeYAML which is bundled with Velocity's classpath.
 */
public class ProxyConfig {

    private final Path dataDirectory;
    private final Logger logger;
    private Map<String, List<String>> groups = new HashMap<>();

    public ProxyConfig(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    /**
     * Loads config.yml from the data directory.
     * If the file doesn't exist, copies the default from resources.
     */
    @SuppressWarnings("unchecked")
    public void load() {
        Path configPath = dataDirectory.resolve("config.yml");

        // Copy default if missing
        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(dataDirectory);
                try (InputStream in = getClass().getClassLoader()
                        .getResourceAsStream("config.yml")) {
                    if (in != null) {
                        Files.copy(in, configPath);
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to create default config.yml", e);
                return;
            }
        }

        // Parse YAML
        try (InputStream in = Files.newInputStream(configPath)) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            if (root == null) {
                logger.warn("config.yml is empty");
                return;
            }

            groups.clear();
            Map<String, Object> groupsSection = (Map<String, Object>) root.get("groups");
            if (groupsSection == null) {
                logger.warn("No 'groups' section found in config.yml");
                return;
            }

            for (Map.Entry<String, Object> entry : groupsSection.entrySet()) {
                String groupName = entry.getKey();
                Map<String, Object> groupData = (Map<String, Object>) entry.getValue();
                List<String> servers = (List<String>) groupData.get("servers");
                if (servers != null) {
                    groups.put(groupName, new ArrayList<>(servers));
                    logger.info("Group '{}': {}", groupName, servers);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load config.yml", e);
        }
    }

    public Map<String, List<String>> getGroups() {
        return groups;
    }
}
