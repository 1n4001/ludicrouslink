plugins {
    base
    alias(libs.plugins.node.gradle)
}

node {
    // Version of node to use.
    version.set("20.10.0")

    // Version of npm to use.
    npmVersion.set("10.2.3")

    // If true, it will download node using above parameters.
    // If false, it will try to use globally installed node.
    download.set(true)
}

tasks.named("npmInstall") {
    inputs.file("package.json")
    outputs.dir("node_modules")
}

tasks.register<Copy>("copyDecoderAssets") {
    dependsOn(tasks.named("npmInstall"))
    into("public")
    
    from("node_modules/broadway-player/Player/Decoder.js")
    
    from("node_modules/tinyh264/lib") {
        include("TinyH264.js", "TinyH264Worker.js", "TinyH264Decoder.js")
        into("tinyh264")
    }
}

tasks.named("npm_run_build") {
    dependsOn(tasks.named("npmInstall"))
    dependsOn("copyDecoderAssets")

    // Source files that trigger a rebuild
    inputs.dir("src")
    inputs.file("index.html")
    inputs.file("tsconfig.json")
    inputs.file("tsconfig.app.json")
    inputs.file("tsconfig.node.json")
    inputs.file("vite.config.ts")

    outputs.dir("dist")
}

// Make the standard 'build' task depend on npm_run_build
tasks.named("build") {
    dependsOn(tasks.named("npm_run_build"))
}
