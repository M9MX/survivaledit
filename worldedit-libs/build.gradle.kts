tasks.register("build") {
    dependsOn(":worldedit-libs:core:build", ":worldedit-libs:core:ap:build")
}
