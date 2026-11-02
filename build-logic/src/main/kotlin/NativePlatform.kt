import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

/**
 * The platform of the build machine, as the archives of the native images name it: `linux-x86_64`,
 * `linux-aarch_64`, `osx-aarch_64`, `windows-x86_64`... GraalVM can't cross-compile, so the native
 * images are built for this one.
 */
fun ProviderFactory.nativePlatform(): Provider<String> = systemProperty("os.name").zip(systemProperty("os.arch")) { name, arch ->
    val os = name.lowercase().let {
        when {
            it.contains("win") -> "windows"
            it.contains("mac") || it.contains("darwin") -> "osx"
            else -> "linux"
        }
    }
    val cpu = when (arch.lowercase()) {
        "amd64", "x86_64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch_64"
        else -> arch.lowercase()
    }
    "$os-$cpu"
}
