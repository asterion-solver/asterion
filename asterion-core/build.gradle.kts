plugins {
    id("me.champeau.asterion.library")
    id("me.champeau.asterion.jmh")
    // helpers which the tests of the other modules share, such as Locales
    `java-test-fixtures`
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
    retry {
        // the lanes of the search run in parallel: the quad which finds the field, and so the solution,
        // can differ from a run to the next, and the accuracy of some synthetic fields is at the limit
        maxRetries = 2
        filter {
            includeClasses.add("me.champeau.asterion.solver.PlateSolverTest")
        }
    }
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

