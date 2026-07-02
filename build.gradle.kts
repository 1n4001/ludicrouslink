plugins {
    base
}

group = "com.cesicorp"
version = "1.0-SNAPSHOT"

// Tasks for frontend and backend are now in their respective subprojects


allprojects {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven { url = uri("https://maven.scijava.org/content/repositories/public/") }
    }
}




tasks.register("setupAndroidSdk") {
    group = "setup"
    description = "Downloads and configures Android SDK"
    
    val sdkDir = file(".android-sdk")
    val cmdlineToolsZip = file("commandlinetools.zip")
    // Latest command line tools for Windows as of late 2024/2025
    val cmdlineToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    
    doLast {
        if (!sdkDir.exists()) {
            sdkDir.mkdirs()
        }

        // 1. Download Command Line Tools
        if (!cmdlineToolsZip.exists()) {
            println("Downloading Android Command Line Tools...")
            val url = java.net.URL(cmdlineToolsUrl)
            url.openStream().use { input ->
                java.io.FileOutputStream(cmdlineToolsZip).use { output ->
                    input.copyTo(output)
                }
            }
            println("Download complete.")
        }

        // 2. Extract
        val cmdlineDir = File(sdkDir, "cmdline-tools")
        if (!cmdlineDir.exists()) {
            println("Extracting Command Line Tools...")
            copy {
                from(zipTree(cmdlineToolsZip))
                into(sdkDir)
            }
            // Move content to expected structure: cmdline-tools/latest
            val extractedRoot = File(sdkDir, "cmdline-tools")
            val latestDir = File(extractedRoot, "latest")
            if (!latestDir.exists()) {
                // The zip usually contains a "cmdline-tools" folder
                // We need to move its content to "cmdline-tools/latest"
                // But the zip extracts as "cmdline-tools", so we rename it to "latest" and move it under a new "cmdline-tools"
                val tempDir = File(sdkDir, "temp_cmdline")
                extractedRoot.renameTo(tempDir)
                extractedRoot.mkdirs()
                tempDir.renameTo(latestDir)
            }
        }

        // 3. Create local.properties
        val localProperties = file("local.properties")
        if (!localProperties.exists()) {
             // Escape backslashes for Windows path
            val sdkPathStr = sdkDir.absolutePath.replace("\\", "\\\\")
            localProperties.writeText("sdk.dir=$sdkPathStr")
            println("Created local.properties pointing to ${sdkDir.absolutePath}")
        }
        
        // 4. Install Packages matches ludicrouslinkAndroid/app/build.gradle.kts
        val platformDir = File(sdkDir, "platforms/android-36")
        val buildToolsDir = File(sdkDir, "build-tools/35.0.0")
        val platformToolsDir = File(sdkDir, "platform-tools")  // adb lives here
        
        if (!platformDir.exists() || !buildToolsDir.exists() || !platformToolsDir.exists()) {
            println("Installing Android SDK packages...")
            val sdkManagerHost = file("${sdkDir.path}/cmdline-tools/latest/bin/sdkmanager.bat")
            val sdkManagerPath = sdkManagerHost.absolutePath
            
            println("Accepting licenses via PowerShell...")
            val psCommand = "(1..20 | ForEach-Object { 'y'; Start-Sleep -Milliseconds 100 }) | & '${sdkManagerPath}' --licenses"
            
            ProcessBuilder("powershell", "-Command", psCommand)
                .redirectErrorStream(true)
                .start()
                .apply {
                    inputStream.bufferedReader().forEachLine { println(it) }
                    waitFor()
                }

            println("Downloading and installing packages (this may take 5-10 minutes)...")
            val psInstallCommand = "(1..20 | ForEach-Object { 'y'; Start-Sleep -Milliseconds 100 }) | & '${sdkManagerPath}' \"platform-tools\" \"platforms;android-36\" \"build-tools;35.0.0\""
            
            ProcessBuilder("powershell", "-Command", psInstallCommand)
                .redirectErrorStream(true)
                .start()
                .apply {
                    inputStream.bufferedReader().forEachLine { println(it) }
                    waitFor()
                }
        } else {
            println("Android SDK packages already installed.")
        }
        
        println("Android SDK setup complete.")
    }
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds everything"
    dependsOn("setupAndroidSdk", ":frontend:build", ":backend:build", ":ludicrouslinkAndroid:app:assembleDebug")
}

tasks.register("runBackend") {
    group = "application"
    description = "Runs the backend application (and builds frontend)"
    dependsOn(":backend:run")
}
