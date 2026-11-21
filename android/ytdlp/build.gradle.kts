plugins {
    id("com.android.library")
}

android {
    namespace = "com.cdg.browser.ytdlp"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("io.github.farimarwat:youtubedl-boom-lite:1.0.24")
    implementation("io.github.farimarwat:youtubedl-boom-commons:1.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.12.7.1")

    testImplementation("junit:junit:4.13.2")
}
