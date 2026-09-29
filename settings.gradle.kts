rootProject.name = "MikuTP"

dependencyResolutionManagement {
    repositories {
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://repo.helpch.at/releases/") { name = "helpch" }
        mavenCentral()
    }
}

include(":common", ":paper", ":velocity")
