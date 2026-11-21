plugins { id("com.android.application") }
android {
    namespace = "com.roy.bluetoothglassesrouter"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.roy.bluetoothglassesrouter"
        minSdk = 26
        targetSdk = 34
        versionCode = 39
        versionName = "0.38-session-done-wiring"
        testInstrumentationRunner = "com.roy.bluetoothglassesrouter.SmokeInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}
dependencies { testImplementation("junit:junit:4.13.2") }
