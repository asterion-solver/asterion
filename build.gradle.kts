import org.jreleaser.model.Active
import org.jreleaser.model.Distribution.DistributionType
import org.jreleaser.model.Stereotype

plugins {
    base
    id("org.jreleaser") version "1.26.0"
}

val executableName = providers.gradleProperty("executableName").get()
val displayName = providers.gradleProperty("displayName").get()

// The platforms for which native executables are released. The archives are built by the
// `nativeZip` task on each platform, and gathered in the `artifacts` directory by the release
// workflow before JReleaser runs.
val platforms = listOf("linux-x86_64", "linux-aarch_64", "osx-x86_64", "osx-aarch_64", "windows-x86_64")

jreleaser {
    project {
        name = executableName
        description = "Asterion Solver, a fast blind astrometric plate solver"
        longDescription = "Finds the celestial coordinates, scale and orientation of astronomical images, without any hint."
        authors = listOf("Cédric Champeau")
        license = "Apache-2.0"
        inceptionYear = "2026"
        copyright = "2026 the original author or authors"
        stereotype = Stereotype.CLI
        links {
            // the repository of the `origin` remote
            homepage = "{{repoUrl}}"
            documentation = "{{repoUrl}}#readme"
        }
    }
    release {
        github {
            // the repository is the one of the `origin` remote
            tagName = "v{{projectVersion}}"
            releaseName = "$displayName {{projectVersion}}"
            changelog {
                formatted = Active.ALWAYS
                preset = "conventional-commits"
                contributors {
                    enabled = false
                }
            }
        }
    }
    checksum {
        individual = false
    }
    distributions {
        create(executableName) {
            distributionType = DistributionType.BINARY
            executable {
                windowsExtension = "exe"
            }
            for (platform in platforms) {
                artifact {
                    path = layout.projectDirectory.file("artifacts/$executableName-{{projectVersion}}-$platform.zip")
                    this.platform = platform
                }
            }
        }
    }
}
