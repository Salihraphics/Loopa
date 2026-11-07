plugins {
    alias(libs.plugins.loopa.android.library)
    alias(libs.plugins.loopa.android.compose)
}

android { namespace = "com.loopa.core.ui" }

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
}
