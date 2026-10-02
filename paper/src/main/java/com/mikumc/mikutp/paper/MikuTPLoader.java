package com.mikumc.mikutp.paper;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

import java.util.List;

/**
 * Resolves the runtime libraries for paper-plugin.yml (which, unlike
 * plugin.yml, has no "libraries" key). Everything is downloaded from Maven
 * Central at server startup; nothing is shaded into the plugin jar.
 */
public final class MikuTPLoader implements PluginLoader {

    /** Runtime libraries, resolved from Maven Central at load time. */
    public static final List<String> LIBRARIES = List.of(
            "org.xerial:sqlite-jdbc:3.46.1.3",
            "com.zaxxer:HikariCP:6.2.1",
            "redis.clients:jedis:5.1.0");

    @Override
    public void classloader(PluginClasspathBuilder classpath) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        for (String coordinates : LIBRARIES) {
            resolver.addDependency(new Dependency(new DefaultArtifact(coordinates), null));
        }
        resolver.addRepository(new RemoteRepository.Builder("central", "default",
                MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        classpath.addLibrary(resolver);
    }
}
