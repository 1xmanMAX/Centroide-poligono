import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---------------------------------------------------------------------------------------------------------------
// Firma de SUBIDA para Google Play (Play App Signing re-firma con la clave de la app). Nada de esto va en el repo:
// se lee de keystore.properties o local.properties (ambos en .gitignore) o de variables de entorno, en este orden.
//   UPLOAD_STORE_FILE=/ruta/fuera/del/repo/upload-keystore.jks
//   UPLOAD_STORE_PASSWORD=...   UPLOAD_KEY_ALIAS=upload   UPLOAD_KEY_PASSWORD=...
// Sin estas claves la release NO se firma con la clave de debug: las tareas de release fallan con un mensaje claro.
// ---------------------------------------------------------------------------------------------------------------
val uploadSigning: Map<String, String?> = run {
    val props = Properties()
    listOf("keystore.properties", "local.properties").map { rootProject.file(it) }.filter { it.isFile }
        .reversed() // keystore.properties manda sobre local.properties
        .forEach { f -> f.inputStream().use { props.load(it) } }
    listOf("UPLOAD_STORE_FILE", "UPLOAD_STORE_PASSWORD", "UPLOAD_KEY_ALIAS", "UPLOAD_KEY_PASSWORD").associateWith { k ->
        (props.getProperty(k) ?: System.getenv(k))?.trim()?.takeIf { it.isNotEmpty() }
    }
}
val uploadSigningMissing: List<String> = buildList {
    uploadSigning.filterValues { it == null }.keys.forEach { add(it) }
    uploadSigning["UPLOAD_STORE_FILE"]?.let { if (!file(it).isFile) add("UPLOAD_STORE_FILE (no existe: $it)") }
}

android {
    namespace = "com.scannerpromax"
    // Google Play exige targetSdk 36 (Android 16) a apps nuevas y actualizaciones desde el 31-08-2026.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.scannerpromax"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // Solo español (textos de la app) e inglés: quita cientos de traducciones de AndroidX/Material
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

    signingConfigs {
        if (uploadSigningMissing.isEmpty()) {
            create("release") {
                storeFile = file(uploadSigning.getValue("UPLOAD_STORE_FILE")!!)
                storePassword = uploadSigning["UPLOAD_STORE_PASSWORD"]
                keyAlias = uploadSigning["UPLOAD_KEY_ALIAS"]
                keyPassword = uploadSigning["UPLOAD_KEY_PASSWORD"]
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            // R8 completo (modo full por defecto en AGP 8) + recorte de recursos.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // NUNCA la clave de debug: sin keystore de subida la release queda sin firmar y las tareas fallan (abajo).
            signingConfig = signingConfigs.findByName("release")
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
            // Por si dos dependencias traen libc++_shared.so (OpenCV la incluye): se queda con una.
            pickFirsts += setOf("**/libc++_shared.so")
            // Sin x86 de 32 bits (como los splits): ONNX Runtime y OpenCV lo traen y engordaría el APK universal.
            excludes += setOf("lib/x86/**")
            // ONNX Runtime nativo (libonnxruntime.so) VA DENTRO del AAB/APK: la política de Google Play prohíbe
            // descargar código ejecutable (.so/.dex/.jar). Con AAB cada teléfono recibe solo su ABI (~13 MB).
        }
    }
}

// Release sin keystore de subida: falla ANTES de empaquetar, con instrucciones (nunca se firma con la clave de debug).
tasks.matching { t ->
    t.name.endsWith("Release") && listOf("pre", "bundle", "assemble", "package", "sign", "install").any { t.name.startsWith(it) }
}.configureEach {
    doFirst {
        if (uploadSigningMissing.isNotEmpty()) throw GradleException(
            "Release sin firma de subida. Faltan: ${uploadSigningMissing.joinToString()}.\n" +
                "Defínelas en keystore.properties o local.properties (fuera del repo, en .gitignore) o como variables de " +
                "entorno: UPLOAD_STORE_FILE, UPLOAD_STORE_PASSWORD, UPLOAD_KEY_ALIAS, UPLOAD_KEY_PASSWORD. " +
                "La release nunca se firma con la clave de debug."
        )
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

    // PDF (capa de texto invisible para PDF con búsqueda + compresión)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Descarga del lector Indra / la maquetación en segundo plano (servicio en primer plano + notificación)
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // IA en la nube (opcional, con la clave del usuario): SDK oficial de Anthropic para Java (Claude Haiku 5.5) y
    // OkHttp (ya lo trae el SDK) para DeepSeek, que no tiene SDK oficial para Java/Kotlin.
    implementation("com.anthropic:anthropic-java-client-okhttp:2.72.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // OCR Indra (único OCR de la app) y maquetación de páginas (PP-DocLayoutV3, opcional) con ONNX Runtime (OpenCV 4.10
    // no carga PP-DocLayoutV3 y OpenCV 5 da otras detecciones, banco recon2_app). Su libonnxruntime.so NO va en el APK (ver
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
        // ZipEntryFetcherTest contra el AAR real de Maven Central (red): -PortReal=DIR
        "ortReal" to "ort.real",
        // Capturas del rediseño (src/test/.../screenshots): carpeta de salida de los PNG.
        "screenshots" to "screenshots.out",
        // Indra (IndraParityTest): carpeta de pesos latín y referencias Python; -PindraEval=DIR escribe <id>.txt del
        // subconjunto OCR del banco (ia_eval/subset.csv) para puntuar con tools/ia_score.py.
        "indraDir" to "indra.dir", "indraParity" to "indra.parity", "indraEval" to "indra.eval", "indraDebug" to "indra.debug",
        // IndraAfinadoEvalTest: -PindraOpts="src=GEO;prep=1;doubt=0.3;lex=1;vocab=a,b;set=all" (ver el test).
        "indraOpts" to "indra.opts",
        // Reconstrucción con IA (DocRenderersTest): carpeta donde dejar los PDF de muestra.
        "docgenOut" to "docgen.out",
        // JevOcrEvalTest (banco del OCR con Jev, red): -PjevOcr=DIR [-PjevOpts="minp=0.85;lines=1"]. Clave: fichero
        // /tmp/claude-0/bench/.jev_key (nunca en el repo).
        "jevOcr" to "jev.ocr", "jevOpts" to "jev.opts",
        // JevIdeasEvalTest (captura, duplicados, firmas con la API real): -PjevIdeas=DIR con DIR/list.tsv (scratch jev_impl).
        "jevIdeas" to "jev.ideas",
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
