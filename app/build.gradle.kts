plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.scannerpromax"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scannerpromax"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // Solo español (textos de la app) e inglés: quita cientos de traducciones de AndroidX/Material/ML Kit
        // (APK y resources.arsc más pequeños = menos memoria y arranque algo más rápido).
        resourceConfigurations += listOf("es", "en")
    }

    // APKs por ABI: cada celular descarga solo las librerías nativas que necesita (APK más liviano).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            // El APK universal (las 3 ABIs de OpenCV, mucho más pesado) solo sirve para repartir un único archivo.
            // Instala el de tu ABI (armeabi-v7a en la mayoría de gama baja / Android Go). Desactivable con
            // ./gradlew assembleRelease -PuniversalApk=false
            isUniversalApk = (project.findProperty("universalApk") as String?)?.toBoolean() ?: true
        }
    }

    buildTypes {
        release {
            // R8 completo (modo full por defecto en AGP 8) + recorte de recursos.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Release = perfil de referencia (src/main/baseline-prof.txt) compilado a assets/dexopt/baseline.prof;
    // ProfileInstaller lo aplica al instalar para que ART precompile las rutas calientes (sin jank en el 1er uso).
    packaging {
        // Recursos de criptografía post-cuántica de BouncyCastle (dependencia de pdfbox) que la app no usa: ~7 MB.
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "org/bouncycastle/pqc/**") }
        // Librerías nativas comprimidas: APK mucho más liviano para descargar/compartir.
        jniLibs { useLegacyPackaging = true }
    }
}

composeCompiler {
    // Strong skipping ya viene activado por defecto desde Kotlin 2.0.20 (aquí 2.0.21).
    // Clases de dominio inmutables (domain.*) y java.io.File marcadas como estables: las celdas que las reciben
    // se saltan la recomposición si no cambian (comparación por equals en vez de por identidad).
    stabilityConfigurationFile = project.layout.projectDirectory.file("compose_compiler_config.conf")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Instala el perfil de referencia (baseline-prof.txt) también en instalaciones fuera de Play (APK directo).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Cámara
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Visión por computadora (detección de bordes, perspectiva, mejora de imagen)
    implementation("org.opencv:opencv:4.10.0")

    // OCR en el dispositivo (sin internet)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // PDF (capa de texto invisible para PDF con búsqueda + compresión)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
