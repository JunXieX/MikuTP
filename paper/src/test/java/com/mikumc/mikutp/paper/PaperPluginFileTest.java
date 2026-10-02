package com.mikumc.mikutp.paper;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards paper-plugin.yml: the declared permissions, loader and dependencies
 * must stay in sync with the code (PermissionNodes, MikuTPLoader). */
class PaperPluginFileTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadDescription() throws Exception {
        try (var in = getClass().getResourceAsStream("/paper-plugin.yml")) {
            if (in == null) {
                throw new IllegalStateException("paper-plugin.yml missing from resources");
            }
            return new Yaml().load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void permissionsMatchRegistry() throws Exception {
        Map<String, Object> description = loadDescription();
        Map<String, Object> permissions = (Map<String, Object>) description.get("permissions");
        var declared = List.copyOf(permissions.keySet());
        var registry = PermissionNodes.NODES.stream().map(PermissionNodes.Node::node).toList();
        assertEquals(registry, declared, "paper-plugin.yml permissions must match PermissionNodes.NODES");

        for (var node : PermissionNodes.NODES) {
            var entry = (Map<String, Object>) permissions.get(node.node());
            assertEquals(node.description(), entry.get("description"), node.node() + " description");
            assertEquals(node.def(), String.valueOf(entry.get("default")).toLowerCase(), node.node() + " default");
        }
    }

    @Test
    void descriptionKeys() throws Exception {
        Map<String, Object> description = loadDescription();
        assertEquals("com.mikumc.mikutp.paper.MikuTPPlugin", description.get("main"));
        assertEquals("com.mikumc.mikutp.paper.MikuTPLoader", description.get("loader"));
        assertEquals("26.2", String.valueOf(description.get("api-version")));
        assertEquals(Boolean.TRUE, description.get("folia-supported"));
        assertEquals("MikuTP", description.get("name"));

        var dependencies = (Map<String, Object>) description.get("dependencies");
        var server = (Map<String, Object>) dependencies.get("server");
        var papi = (Map<String, Object>) server.get("PlaceholderAPI");
        assertEquals("BEFORE", String.valueOf(papi.get("load")));
        assertEquals(Boolean.FALSE, papi.get("required"));
        assertEquals(Boolean.TRUE, papi.get("join-classpath"));
    }

    @Test
    void loaderResolvesExpectedLibraries() {
        assertEquals(List.of(
                "org.xerial:sqlite-jdbc:3.46.1.3",
                "com.zaxxer:HikariCP:6.2.1",
                "redis.clients:jedis:5.1.0"), MikuTPLoader.LIBRARIES);
    }
}
