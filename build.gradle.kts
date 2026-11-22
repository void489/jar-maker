plugins {
    java
}

group = "dev.mobzone"
version = "3.14.3"

// Overridable on the command line: -PpaperApi=... -PmythicVersion=...
val paperApi = providers.gradleProperty("paperApi").getOrElse("26.1.2.build.+")
val mythicVersion = providers.gradleProperty("mythicVersion").getOrElse("5.12.1")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://mvn.lumine.io/repository/maven-public/")
    maven("https://repo.helpch.at/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    compileOnly("io.lumine:Mythic-Dist:$mythicVersion")
    compileOnly("me.clip:placeholderapi:2.11.6")
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

// ---- optional add-on: MobZone-Hover (tooltips via PacketEvents). Kept separate so a problem here can't break the main jar.
sourceSets {
    create("hover") {
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
        runtimeClasspath += output + compileClasspath
    }
}

repositories {
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    "hoverCompileOnly"("com.github.retrooper:packetevents-spigot:2.12.0")
}

tasks.named<JavaCompile>("compileHoverJava") {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.isFailOnError = false
}

tasks.named<ProcessResources>("processHoverResources") {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

val hoverJar = tasks.register<Jar>("hoverJar") {
    archiveBaseName.set("MobZone-Hover")
    from(sourceSets["hover"].output)
}

tasks.named("build") {
    dependsOn(hoverJar)
}

// ---- optional add-on: MobZone-Skins (zombie and boss skins via LibsDisguises, uploaded on the website).
sourceSets {
    create("skins") {
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
        runtimeClasspath += output + compileClasspath
    }
}

// LibsDisguises is called through reflection, so nothing extra is needed to compile this add-on.

tasks.named<JavaCompile>("compileSkinsJava") {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.isFailOnError = false
}

tasks.named<ProcessResources>("processSkinsResources") {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

val skinsJar = tasks.register<Jar>("skinsJar") {
    archiveBaseName.set("MobZone-Skins")
    from(sourceSets["skins"].output)
}

tasks.named("build") {
    dependsOn(skinsJar)
}
