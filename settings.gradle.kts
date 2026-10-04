pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "asterion"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include("asterion-core")
include("asterion-cli")
