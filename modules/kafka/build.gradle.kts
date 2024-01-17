import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    id("dev.clojurephant.clojure")
    `maven-publish`
    signing
    kotlin("jvm")
}

publishing {
    publications.create("maven", MavenPublication::class) {
        pom {
            name.set("XTDB Kafka")
            description.set("XTDB Kafka")
        }
    }
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

dependencies {
    api(project(":api"))
    api(project(":core"))

    api("org.apache.kafka", "kafka-clients", "3.1.0")

    api(kotlin("stdlib-jdk8"))
}
