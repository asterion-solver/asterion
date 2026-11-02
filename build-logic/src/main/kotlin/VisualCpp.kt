import java.io.File

/**
 * The script of Visual C++ which sets up its environment, `vcvarsall.bat`, found with the installer of
 * Visual Studio the way native-image finds it, or null when it isn't installed. Shells where the
 * environment is set up already have `cl` in their `PATH`.
 */
fun vcvarsall(): File? {
    val programs = System.getenv("ProgramFiles(x86)") ?: return null
    val vswhere = File(programs, "Microsoft Visual Studio/Installer/vswhere.exe")
    if (!vswhere.isFile) {
        return null
    }
    val process = ProcessBuilder(vswhere.absolutePath, "-latest", "-products", "*",
        "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath")
        .redirectErrorStream(true)
        .start()
    val installation = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() != 0 || installation.isEmpty()) {
        return null
    }
    return File(installation, "VC/Auxiliary/Build/vcvarsall.bat").takeIf { it.isFile }
}
