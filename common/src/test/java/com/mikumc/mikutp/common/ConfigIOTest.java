package com.mikumc.mikutp.common;

import com.mikumc.mikutp.common.config.ConfigIO;
import com.mikumc.mikutp.common.config.MikuTPConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigIOTest {

    @TempDir
    Path dir;

    @Test
    void loadsSnakeCaseIntoCamelCaseFields() throws IOException {
        Path file = dir.resolve("config.json");
        MikuTPConfig config = ConfigIO.loadOrCreate(file, "{\"wild\": {\"min_radius\": 120, \"blocked_biome_tags\": [\"is_ocean\"]}}", MikuTPConfig.class);

        assertEquals(120, config.wild.minRadius);
        assertEquals(1, config.wild.blockedBiomeTags.size());
        // Defaults survive partial files.
        assertEquals(5000, config.wild.maxRadius);
        assertEquals("SQLITE", config.storage.type);
    }

    @Test
    void writesBundledDefaultWhenMissing() throws IOException {
        Path file = dir.resolve("sub").resolve("config.json");
        MikuTPConfig config = ConfigIO.loadOrCreate(file, "{\"config_version\": 3}", MikuTPConfig.class);

        assertTrue(java.nio.file.Files.exists(file));
        assertEquals(3, config.configVersion);
        assertEquals(5, config.home.defaultLimit);
    }

    @Test
    void roundTripKeepsValues() throws IOException {
        Path file = dir.resolve("config.json");
        MikuTPConfig first = ConfigIO.loadOrCreate(file, "{}", MikuTPConfig.class);
        first.wild.maxRadius = 4321;
        first.crossServer.serverId = "lobby";
        ConfigIO.save(file, first);

        MikuTPConfig second = ConfigIO.loadOrCreate(file, "{}", MikuTPConfig.class);
        assertEquals(4321, second.wild.maxRadius);
        assertEquals("lobby", second.crossServer.serverId);
    }
}
