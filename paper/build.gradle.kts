plugins { `java-library` }

base {
    archivesName = "MikuTP-Paper"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("com.zaxxer:HikariCP:6.2.1")
    compileOnly(project(":common"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.yaml:snakeyaml:2.2")
    // compileOnly deps are absent from the test classpath; the loader test needs them.
    testImplementation("io.papermc.paper:paper-api:26.2.build.129-stable")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    dependsOn(":common:classes")
    from(project(":common").sourceSets.main.get().output) {
        exclude("META-INF/**")
    }
}
