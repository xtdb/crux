import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    id("dev.clojurephant.clojure")
    `maven-publish`
    signing
    kotlin("jvm")
}

ext {
    set("labs", true)
}

publishing {
    publications.create("maven", MavenPublication::class) {
        pom {
            name.set("XTDB FlightSQL Server")
            description.set("XTDB FlightSQL Server")
        }
    }
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

dependencies {
    api(project(":api"))
    api(project(":core"))

    api("org.apache.arrow", "arrow-vector", "14.0.0")
    api("org.apache.arrow", "flight-sql", "14.0.0")

    api(kotlin("stdlib-jdk8"))

    testImplementation(project(":"))
    testImplementation(project(":http-client-clj"))

    // brings in vendored SLF4J (but doesn't change the class names). naughty.
    // https://github.com/apache/arrow/issues/34516
    testImplementation("org.apache.arrow", "flight-sql-jdbc-driver", "14.0.0")

    testImplementation("pro.juxt.clojars-mirrors.com.github.seancorfield", "next.jdbc", "1.2.674")
}
