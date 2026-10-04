plugins {
    id("me.champeau.asterion.library")
}

description = "Asterion Solver: a fast blind astrometric plate solving library"

dependencies {
    implementation(libs.fits)
}

tasks.jar {
    // the notices of third-party software which the library is based on
    from(rootProject.layout.projectDirectory.dir("third-party")) {
        into("META-INF/third-party")
    }
    manifest {
        attributes("Automatic-Module-Name" to "me.champeau.asterion.core")
    }
}

tasks.test {
    // a list of image files which the decoders are compared with ImageIO on, see ImageCorpusTest
    val imageCorpus = providers.gradleProperty("imageCorpus").getOrElse("")
    systemProperty("asterion.imageCorpus", imageCorpus)
    // a list of image files to benchmark the decoders on, see ImageDecodersBenchmark
    val imageBenchmark = providers.gradleProperty("imageBenchmark").getOrElse("")
    systemProperty("asterion.imageBenchmark", imageBenchmark)
    if (imageCorpus.isNotEmpty() || imageBenchmark.isNotEmpty()) {
        // the images aren't inputs of the task: the comparison or the benchmark always runs
        outputs.upToDateWhen { false }
        // large images are decoded twice, at full resolution
        maxHeapSize = "8g"
    }
}
