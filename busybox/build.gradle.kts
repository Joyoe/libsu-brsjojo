plugins {
    id("com.android.library")
    id("com.github.dcendents.android-maven")
}

group="com.github.joyoe.libsu"

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    api(project(":core"))
}
