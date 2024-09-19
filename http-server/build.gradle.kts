import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    id("dev.clojurephant.clojure")
    `maven-publish`
    signing
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
}

publishing {
    publications.create("maven", MavenPublication::class) {
        pom {
            name.set("XTDB HTTP Server")
            description.set("XTDB HTTP Server")
        }
    }
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

dependencies {
    api(project(":xtdb-api"))
    api(project(":xtdb-core"))

    api("ring", "ring-core", "1.10.0")
    api("info.sunng", "ring-jetty9-adapter", "0.22.4")
    api("org.eclipse.jetty", "jetty-alpn-server", "10.0.15")

    api("metosin", "muuntaja", "0.6.8")
    api("metosin", "jsonista", "0.3.3")
    api("metosin", "reitit-core", "0.5.15")
    api("metosin", "reitit-interceptors", "0.5.15")
    api("metosin", "reitit-ring", "0.5.15")
    api("metosin", "reitit-http", "0.5.15")
    api("metosin", "reitit-sieppari", "0.5.15")
    api("metosin", "reitit-spec", "0.5.15")

    api("com.cognitect", "transit-clj", "1.0.329")

    api(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.6.0")

    testImplementation(project(":"))
    testImplementation(project(":xtdb-http-client-jvm"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("pro.juxt.clojars-mirrors.hato", "hato", "0.8.2")
    // hato uses cheshire for application/json encoding
    testImplementation("cheshire", "cheshire", "5.12.0")
}

tasks.javadoc.get().enabled = false
