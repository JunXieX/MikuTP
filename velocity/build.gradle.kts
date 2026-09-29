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
}

tasks.jar {
    dependsOn(":common:classes")
    from(project(":common").sourceSets.main.get().output) {
        exclude("META-INF/**")
    }
}
