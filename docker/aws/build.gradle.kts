import xtdb.DataReaderTransformer

plugins {
    java
    application
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":xtdb-core"))
    implementation(project(":xtdb-http-server"))
    implementation(project(":modules:xtdb-kafka"))
    implementation(project(":modules:xtdb-aws"))
    implementation("ch.qos.logback", "logback-classic", "1.4.5")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

application {
    mainClass.set("clojure.main")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Version" to project.version,
        )
    }
}

tasks.shadowJar {
    archiveBaseName.set("xtdb")
    archiveVersion.set("")
    archiveClassifier.set("aws")
    mergeServiceFiles()
    transform(DataReaderTransformer())
}
