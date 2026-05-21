import org.incendo.cloudbuildlogic.city
import org.incendo.cloudbuildlogic.jmp

plugins {
    id("org.incendo.cloud-build-logic.publishing")
}

indra {
    github("Incendo", "cloud-discord") {
        ci(true)
    }
    mitLicense()

    configurePublications {
        pom {
            developers {
                city()
                jmp()
            }
        }
    }
}

publishing {
    repositories {
        maven {
            name = "sunnyinfra-snapshots"
            url = uri("https://repo.sunnyinfra.cloud/development")
            credentials {
                username = (project.findProperty("SunnyInfraUsername") ?: System.getenv("SunnyInfraUsername")) as? String
                password = (project.findProperty("SunnyInfraPassword") ?: System.getenv("SunnyInfraPassword")) as? String
            }
        }
    }
}
