plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.chess"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.chess"
        minSdk = 24
        targetSdk = 34
        versionCode = 12
        versionName = "3.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign the release APK with the debug key so it can be installed directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Name every output APK with a clear "v2.3" suffix.
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "chess3d-${buildType.name}-v3.1.1.apk"
        }
    }
}

// Archive every built release APK into <root>/apks/. The AGP output dir
// (app/build/outputs/apk/release) is wiped on each build, so without this each
// new version would erase the previous APK. apks/ lives outside build/ and is
// never cleaned, so all versions accumulate there.
tasks.register<Copy>("archiveReleaseApk") {
    from(layout.buildDirectory.dir("outputs/apk/release"))
    include("*.apk")
    into(rootProject.layout.projectDirectory.dir("apks"))
}
afterEvaluate {
    tasks.named("assembleRelease").configure { finalizedBy("archiveReleaseApk") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
