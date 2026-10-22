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

        // Motor de IA local (llama.cpp + mtmd) por JNI: app/src/main/cpp.
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                cppFlags += listOf("-O3")
                // Mismas ABIs que los splits (sin x86 de 32 bits).
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    ndkVersion = "27.3.13750724"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
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
        // java.time (Duration/Instant) en Android 7.x (API 24–25): lo usa el SDK de Anthropic (timeouts, modelos).
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Recursos Android en los tests JVM: los necesitan las capturas de pantalla con Robolectric (src/test/.../screenshots).
    testOptions { unitTests { isIncludeAndroidResources = true } }
    // Release = perfil de referencia (src/main/baseline-prof.txt) compilado a assets/dexopt/baseline.prof;
    // ProfileInstaller lo aplica al instalar para que ART precompile las rutas calientes (sin jank en el 1er uso).
    packaging {
        // Recursos de criptografía post-cuántica de BouncyCastle (dependencia de pdfbox) que la app no usa: ~7 MB.
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "org/bouncycastle/pqc/**") }
        // Librerías nativas comprimidas: APK mucho más liviano para descargar/compartir.
        jniLibs {
            useLegacyPackaging = true
            // OpenCV trae su propio libc++_shared.so; el del NDK 27 (llama.cpp) es más nuevo y compatible hacia atrás.
            pickFirsts += setOf("**/libc++_shared.so")
            // Sin x86 de 32 bits (como los splits): ONNX Runtime y OpenCV lo traen y engordaría el APK universal.
            excludes += setOf("lib/x86/**")
            // ONNX Runtime nativo (libonnxruntime.so: 12,9 MB comprimido en arm64) FUERA del APK: se descarga con
            // "Maquetación de páginas" (ai/ModelCatalog.ORT_NATIVE, misma versión) y se precarga con System.load
            // (ai/parts/OrtNative). El puente JNI libonnxruntime4j_jni.so (~36 KB) SE QUEDA: en Android ORT lo carga
            // con System.loadLibrary, que solo busca en las librerías del APK (ignora onnxruntime.native.path).
            excludes += setOf("lib/*/libonnxruntime.so")
        }
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
    // Extensiones del fabricante (AUTO/HDR: multi-cuadro, reducción de ruido y nitidez del propio ISP, como la
    // app de cámara de Samsung). Se usan solo si el equipo las ofrece; si no, CameraX normal.
    implementation("androidx.camera:camera-extensions:$camerax")

    // Visión por computadora (detección de bordes, perspectiva, mejora de imagen)
    implementation("org.opencv:opencv:4.10.0")

    // OCR en el dispositivo (sin internet)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // PDF (capa de texto invisible para PDF con búsqueda + compresión)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Descarga de modelos de IA en segundo plano (servicio en primer plano + notificación)
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // IA en la nube (opcional, con la clave del usuario): SDK oficial de Anthropic para Java (Claude Haiku 5.5) y
    // OkHttp (ya lo trae el SDK) para DeepSeek, que no tiene SDK oficial para Java/Kotlin.
    implementation("com.anthropic:anthropic-java-client-okhttp:2.72.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Maquetación de páginas (PP-DocLayoutV3, descarga opcional): OpenCV 4.10 no carga el modelo (Range/GridSample) y
    // OpenCV 5 da otras detecciones (banco recon2_app) → ONNX Runtime. Su libonnxruntime.so NO va en el APK (ver
    // packaging.jniLibs.excludes): se baja con el modelo. Si cambias la versión, cambia ModelCatalog.ORT_VERSION y
    // los tamaños/sha256 de ModelCatalog.ORT_NATIVE (ModelCatalogTest compara las versiones).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")

    testImplementation("junit:junit:4.13.2")
    // Capturas de pantalla del rediseño en la JVM (Robolectric + gráficos nativos). Solo se ejecutan con
    // ./gradlew testDebugUnitTest -Pscreenshots=DIR --tests '*screenshots*' (ver el final de este archivo).
    testImplementation(composeBom)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Tests JVM con los modelos ONNX del banco (si existen): misma API Java que la de Android.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")
    // OpenCV de escritorio (nativo incluido) para tests JVM de imaging con imágenes sintéticas (detección, orientación):
    // las clases Java de org.opencv:opencv (Android) llaman a este nativo, igual que el banco de pruebas.
    testImplementation("org.openpnp:opencv:4.9.0-0")
}

// Tests JVM con pdfbox-android (PDF reconstruido): sus recursos (cmaps, glyphlist) van en los assets del AAR, que en
// la JVM no están en el classpath; se desempaquetan como recursos de test (pdfbox los busca con getResourceAsStream
// si PDFBoxResourceLoader no está inicializado). No afecta al APK.
val pdfboxTestAssets: Configuration by configurations.creating
dependencies { pdfboxTestAssets("com.tom-roush:pdfbox-android:2.0.27.0@aar") }
val unpackPdfboxTestAssets by tasks.registering(Copy::class) {
    from(pdfboxTestAssets.elements.map { files -> files.map { zipTree(it.asFile) } }) {
        include("assets/com/**")
        eachFile { path = path.removePrefix("assets/") }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("pdfbox-test-resources"))
}
android.sourceSets.getByName("test").resources.srcDir(layout.buildDirectory.dir("pdfbox-test-resources"))
tasks.matching { it.name.startsWith("process") && it.name.endsWith("UnitTestJavaRes") }
    .configureEach { dependsOn(unpackPdfboxTestAssets) }
// Muestras del PDF reconstruido para revisarlas a ojo: ./gradlew testDebugUnitTest -PreconOut=DIR [-PreconImages=DIR]
tasks.withType<Test>().configureEach {
    listOf(
        "reconOut" to "recon.out", "reconImages" to "recon.images",
        // Banco recon2 en la app (Recon2AppBenchTest): salida, páginas, puerto de llama-server, etapas.
        "r2appOut" to "r2app.out", "r2appOnly" to "r2app.only", "r2appPort" to "r2app.port", "r2appStage" to "r2app.stage",
        // ZipEntryFetcherTest contra el AAR real de Maven Central (red): -PortReal=DIR
        "ortReal" to "ort.real",
        // Capturas del rediseño (src/test/.../screenshots): carpeta de salida de los PNG.
        "screenshots" to "screenshots.out",
    ).forEach { (p, sp) ->
        (project.findProperty(p) as String?)?.let { systemProperty(sp, it) }
    }
}

// Las capturas de pantalla (Robolectric, lentas y con descarga de android-all la primera vez) NO forman parte de
// testDebugUnitTest normal: solo corren si se pasa -Pscreenshots=DIR.
//   ./gradlew testDebugUnitTest -Pscreenshots=/tmp/claude-0/bench/rediseno --tests '*screenshots*'
tasks.withType<Test>().configureEach {
    if (project.findProperty("screenshots") == null) exclude("**/screenshots/**")
}
