plugins { `java-library` }

base {
    archivesName = "MikuTP-Velocity"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.2.0")
    compileOnly(project(":common"))

    // The frame-contract test compares against the shared encoder in :common.
    testImplementation(project(":common"))
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
