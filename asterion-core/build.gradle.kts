plugins {
    id("me.champeau.asterion.library")
}

description = "Asterion Solver: a fast blind astrometric plate solving library"

dependencies {
    implementation(libs.fits)
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "me.champeau.asterion.core")
    }
}
