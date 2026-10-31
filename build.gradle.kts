plugins {
    java
}

group = "dev.mobzone"
version = "1.0.0"

// Overridable on the command line: -PpaperApi=... -PmythicVersion=...
val paperApi = providers.gradleProperty("paperApi").getOrElse("26.1.2.build.+")
val mythicVersion = providers.gradleProperty("mythicVersion").getOrElse("5.12.1")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://mvn.lumine.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    compileOnly("io.lumine:Mythic-Dist:$mythicVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
