plugins {
    base
}

// Ensure the frontend is built first
val frontendProject = project(":frontend")

tasks.register<Copy>("copyFrontend") {
    group = "build"
    description = "Copies frontend build artifacts to backend"
    
    val frontendBuildTask = frontendProject.tasks.named("npm_run_build")
    dependsOn(frontendBuildTask)
    
    // Define inputs/outputs for up-to-date checks
    inputs.files(frontendBuildTask.get().outputs.files)
    from(frontendProject.layout.projectDirectory.dir("dist"))
    into(layout.projectDirectory.dir("public"))
}

tasks.register<Exec>("goModTidy") {
    group = "build"
    description = "Runs go mod tidy"
    commandLine("go", "mod", "tidy")
}

tasks.register<Exec>("goBuild") {
    group = "build"
    description = "Builds the Go backend"
    dependsOn("copyFrontend", "goModTidy")
    
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        commandLine("go", "build", "-o", "backend.exe", ".")
    } else {
        commandLine("go", "build", "-o", "backend", ".")
    }
}

// Make the standard 'build' task depend on goBuild
tasks.named("build") {
    dependsOn("goBuild")
}

tasks.register<Exec>("run") {
    group = "application"
    description = "Runs the backend application"
    dependsOn("goBuild")
    
    workingDir = layout.projectDirectory.asFile
    
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        commandLine("cmd", "/c", "backend.exe")
    } else {
        commandLine("./backend")
    }
}
