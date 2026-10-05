plugins {
    id("location.android.library")
}

android {
    namespace = "com.chan.location.service.mock"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.mockito.core)
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
