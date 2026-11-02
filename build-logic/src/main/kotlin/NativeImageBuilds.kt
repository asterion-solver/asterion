import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Lets one native image be built at a time: each build sizes its memory from what is free when it
 * starts, and several builds at once can exhaust the memory of small machines, such as CI runners.
 */
abstract class NativeImageBuilds : BuildService<BuildServiceParameters.None>
