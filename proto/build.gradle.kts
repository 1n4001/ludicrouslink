plugins {
    base
}

// FlatBuffer codegen — downloads flatc and generates Go, TypeScript, and Kotlin.
// The flatc version MUST match the runtime library versions in libs.versions.toml
// and backend/go.mod to avoid FLATBUFFERS_XX_XX_XX constant mismatches.
val flatcVersion = "25.2.10"
val flatcDir = rootProject.layout.projectDirectory.dir("tools/flatc")
val flatcExe = flatcDir.file("flatc.exe").asFile
val flatcZipUrl = "https://github.com/nicktrav/flatbuffers/releases/download/v$flatcVersion/Windows.flatc.binary.zip"

val schemaFiles = fileTree(layout.projectDirectory) { include("*.fbs") }

val goOutDir    = rootProject.layout.projectDirectory.dir("backend/pkg/proto")
val tsOutDir    = rootProject.layout.projectDirectory.dir("frontend/src/proto")
val kotlinOutDir = rootProject.layout.projectDirectory.dir(
    "ludicrouslinkAndroid/app/src/main/java"
)

// Download flatc binary matching our runtime library version
tasks.register("downloadFlatc") {
    group = "codegen"
    description = "Download flatc compiler v$flatcVersion"
    val marker = File(flatcDir.asFile, ".version-$flatcVersion")
    outputs.file(marker)
    onlyIf { !marker.exists() }
    doLast {
        val zipUrl = "https://github.com/google/flatbuffers/releases/download/v$flatcVersion/Windows.flatc.binary.zip"
        val zipFile = File(temporaryDir, "flatc.zip")
        logger.lifecycle("Downloading flatc v$flatcVersion from $zipUrl")
        ant.invokeMethod("get", mapOf("src" to zipUrl, "dest" to zipFile.absolutePath))
        flatcDir.asFile.mkdirs()
        copy {
            from(zipTree(zipFile))
            into(flatcDir.asFile)
        }
        marker.writeText(flatcVersion)
    }
}

tasks.register<Exec>("flatcGo") {
    group = "codegen"
    description = "Generate Go code from FlatBuffer schemas"
    dependsOn("downloadFlatc")
    inputs.files(schemaFiles)
    outputs.dir(goOutDir)
    doFirst { goOutDir.asFile.mkdirs() }
    commandLine(flatcExe.absolutePath, "--go", "-o", goOutDir.asFile.absolutePath,
        *schemaFiles.files.map { it.absolutePath }.toTypedArray())
}

tasks.register<Exec>("flatcTs") {
    group = "codegen"
    description = "Generate TypeScript code from FlatBuffer schemas"
    dependsOn("downloadFlatc")
    inputs.files(schemaFiles)
    outputs.dir(tsOutDir)
    doFirst { tsOutDir.asFile.mkdirs() }
    commandLine(flatcExe.absolutePath, "--ts", "-o", tsOutDir.asFile.absolutePath,
        *schemaFiles.files.map { it.absolutePath }.toTypedArray())
    // flatc emits unused imports that fail strict TS — suppress with @ts-nocheck
    doLast {
        fileTree(tsOutDir) { include("**/*.ts") }.forEach { f ->
            val content = f.readText()
            if (!content.startsWith("// @ts-nocheck")) {
                f.writeText("// @ts-nocheck\n$content")
            }
        }
    }
}

tasks.register<Exec>("flatcKotlin") {
    group = "codegen"
    description = "Generate Kotlin code from FlatBuffer schemas"
    dependsOn("downloadFlatc")
    inputs.files(schemaFiles)
    outputs.dir(kotlinOutDir)
    commandLine(flatcExe.absolutePath, "--kotlin", "-o", kotlinOutDir.asFile.absolutePath,
        *schemaFiles.files.map { it.absolutePath }.toTypedArray())
    // Fix version constant if flatc binary is newer than the Maven runtime
    val runtimeConst = "FLATBUFFERS_${flatcVersion.replace('.', '_')}"
    doLast {
        fileTree(kotlinOutDir) { include("**/ludicrouslink/*.kt") }.forEach { f ->
            val content = f.readText()
            val fixed = content.replace(Regex("""Constants\.FLATBUFFERS_\d+_\d+_\d+"""), "Constants.$runtimeConst")
            if (fixed != content) f.writeText(fixed)
        }
    }
}

tasks.register("flatcGenerate") {
    group = "codegen"
    description = "Generate all FlatBuffer code (Go, TypeScript, Kotlin)"
    dependsOn("flatcGo", "flatcTs", "flatcKotlin")
}

tasks.named("build") {
    dependsOn("flatcGenerate")
}
