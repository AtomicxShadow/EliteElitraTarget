plugins {
    alias(libs.plugins.fabric.loom) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

group = property("maven_group")
version = property("mod_version")

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://meteorclient.com/maven")
}

loom {
    accessWidenerPath.set(file("src/main/resources/elitratarget.accesswidener"))

    mods {
        "elitratarget" {
            sourceSet(sourceSets["main"])
            replaceVersionWithCurrent = true
        }
    }
}

dependencies {
    minecraft(libs.minecraft)
    mappings(libs.yarn.mappings)
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)

    modImplementation(libs.meteor.client)
}

tasks.withType<JavaCompile> {
    options.release.set(21)
}
