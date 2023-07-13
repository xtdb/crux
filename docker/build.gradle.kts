plugins {
    java
    application
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":pgwire-server"))
    implementation(project(":http-server"))
    implementation(project(":modules:jdbc"))
    implementation(project(":modules:kafka"))
    implementation(project(":modules:azure"))
    implementation(project(":modules:google-cloud"))
    implementation(project(":modules:flight-sql"))
    implementation("ch.qos.logback", "logback-classic", "1.4.5")
}

application {
    mainClass.set("clojure.main")
}

tasks.shadowJar {
    archiveBaseName.set("xtdb")
    archiveVersion.set("")
    archiveClassifier.set("standalone")
    mergeServiceFiles()
}
