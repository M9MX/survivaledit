plugins {
    base
}

// Reported in the mod metadata so a jar says what it was built from.
extra["internalVersion"] = project.version.toString()

allprojects {
    group = "com.sk89q.worldedit"
}
