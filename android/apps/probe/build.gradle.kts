plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }

android {
    namespace = "io.ahara.biopticon.probe"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.ahara.biopticon.probe"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
        manifestPlaceholders["appAuthRedirectScheme"] = "io.ahara.biopticon.probe"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":packages:formats"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.health.connect:connect-client:1.2.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    annotationProcessor("androidx.room:room-compiler:2.8.4")
    implementation("net.openid:appauth:0.11.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
}

tasks.withType<Test>().configureEach {
    val fixture = layout.buildDirectory.file("interop/event.json")
    systemProperty("biopticon.interop.file", fixture.get().asFile.absolutePath)
    outputs.file(fixture)
}
