plugins {
    id("location.android.library")
}

android {
    namespace = "com.chan.location.map.osm"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }
}

dependencies {
    implementation(project(":map:api"))
    implementation(project(":core:common"))
    implementation(libs.osmdroid.android)
}
