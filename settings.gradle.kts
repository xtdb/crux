pluginManagement {
    plugins {
        kotlin("jvm") version "1.9.22"
        kotlin("plugin.serialization") version "1.9.22"
        id("org.jetbrains.dokka") version "1.9.10"
    }
}

rootProject.name = "xtdb"

include("api", "core")
project(":api").name = "xtdb-api"
project(":core").name = "xtdb-core"

include("http-server", "http-client-jvm")
project(":http-server").name = "xtdb-http-server"
project(":http-client-jvm").name = "xtdb-http-client-jvm"

include("lang:test-harness")
project(":lang:test-harness").name = "test-harness"

include("docker:standalone", "docker:aws", "docker:azure", "docker:google-cloud")
include("cloud-benchmark", "cloud-benchmark:aws", "cloud-benchmark:azure", "cloud-benchmark:google-cloud", "cloud-benchmark:local")

include("modules:kafka", "modules:kafka-connect", "modules:aws", "modules:azure", "modules:google-cloud")
project(":modules:kafka").name = "xtdb-kafka"
project(":modules:kafka-connect").name = "xtdb-kafka-connect"
project(":modules:aws").name = "xtdb-aws"
project(":modules:azure").name = "xtdb-azure"
project(":modules:google-cloud").name = "xtdb-google-cloud"

include("modules:c1-import", "modules:flight-sql")
project(":modules:flight-sql").name = "xtdb-flight-sql"

include("modules:bench", "modules:datasets")

dependencyResolutionManagement {
    versionCatalogs {
        create("deps") {
            version("arrow", "17.0.0")
            library("arrow-algorithm", "org.apache.arrow", "arrow-algorithm").versionRef("arrow")
            library("arrow-compression", "org.apache.arrow", "arrow-compression").versionRef("arrow")
            library("arrow-vector", "org.apache.arrow", "arrow-vector").versionRef("arrow")
            library("arrow-memory-netty", "org.apache.arrow", "arrow-memory-netty").versionRef("arrow")

            version("arrow-adbc", "0.14.0")
            library("arrow-adbc-core", "org.apache.arrow.adbc", "adbc-core").versionRef("arrow-adbc")
            library("arrow-adbc-fsql", "org.apache.arrow.adbc", "adbc-driver-flight-sql").versionRef("arrow-adbc")

            library("clojure-lang", "org.clojure", "clojure").version("1.12.0-rc1")
        }
    }
}
