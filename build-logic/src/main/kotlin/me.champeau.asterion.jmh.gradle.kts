plugins {
    id("me.champeau.jmh")
}

// Microbenchmarks of the hot loops, in src/jmh: ./gradlew :asterion-core:jmh -Pjmh.includes=Decode
jmh {
    jmhVersion = "1.37"
    fork = 1
    warmupIterations = 3
    iterations = 5
    timeOnIteration = "1s"
    warmup = "1s"
    includes = providers.gradleProperty("jmh.includes").map { listOf(it) }.orElse(listOf())
    jvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
