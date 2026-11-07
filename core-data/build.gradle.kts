plugins {
    alias(libs.plugins.loopa.android.library)
    alias(libs.plugins.loopa.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.loopa.core.data" }

dependencies {
    api(project(":core"))
    api(project(":core-network"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
}
