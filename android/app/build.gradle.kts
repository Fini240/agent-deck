plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Push is optional at build time. The coordinator drops the real Firebase config into
// app/google-services.json; without it the build is explicitly "push unconfigured" and the
// app says so instead of pretending notifications are connected.
val pushConfigured = file("google-services.json").isFile
if (pushConfigured) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
    namespace = "de.finn.agentdeck"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.finn.agentdeck"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.2.3"
        buildConfigField("boolean", "PUSH_CONFIGURED", pushConfigured.toString())
        manifestPlaceholders["pushEnabled"] = pushConfigured.toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                // Robolectric's SDK 36 sandbox needs these on JDK 21.
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                )
                it.maxHeapSize = "3g"
            }
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
        // compileSdk/targetSdk stay at the installed SDK 36 (contract); newer AndroidX/Compose lines need 37.
        disable += setOf("OldTargetApi", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        htmlReport = true
        xmlReport = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

roborazzi {
    outputDir.set(rootProject.layout.projectDirectory.dir("../outputs/android/screenshots"))
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:api"))
    implementation(project(":core:push"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    // Play services pulls an old Fragment; ActivityResult APIs need >= 1.3.
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.play.services.code.scanner)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    debugImplementation(libs.compose.ui.test.manifest)
}
