# ESCÁNER PRO MAX

Escáner de documentos para Android con mejora de imagen avanzada, OCR sin internet y herramientas PDF.

## Funciones
- **Cámara inteligente**: detección de bordes en vivo, autocaptura, multipágina, flash, enfoque al tocar.
- **Modos**: Documento, Libro (2 páginas → separadas), DNI/Tarjeta (anverso + reverso en una hoja A4 a tamaño real), Recibo, Pizarra, Foto.
- **3 filtros**: *Blanco y negro* (texto negro nítido sobre blanco puro, sin sombras ni motas; quita la cuadrícula de color clara de los cuadernos conservando la escritura), *Texto resaltado* (por defecto: sin sombras, papel blanco neutro, tinta reforzada con su color; des-ruido, super-resolución y gamma para poca luz automáticos) y *Color original* (colores fieles, solo recorte y un toque de nitidez). Los filtros antiguos de documentos guardados se siguen renderizando y se muestran como su equivalente.
- **Recorte preciso**: esquinas arrastrables con lupa, perspectiva corregida con relación de aspecto real, enderezado automático del texto y **enderezado de hojas curvadas/combadas** usando las líneas de tablas, cuadrículas o renglones (interruptor "Enderezar hoja curvada" en Limpieza).
- **Limpieza**: quita ruido y rayas automáticamente; borrador manual (reparación inteligente o pintar blanco) con deshacer/rehacer.
- **OCR** en el dispositivo con **Indra** (único lector de texto; se descarga solo la primera vez, ~38 MB) y **PDF con texto buscable**, contraseña opcional.
- **Texto en el PDF** (Ajustes → "Texto en el PDF", también en Exportar): *Solo imagen*, *Buscable* (capa invisible palabra a palabra), *Buscable + texto reconocido* (páginas legibles con el texto, tras cada página o al final) y *Solo texto*. Fuente Liberation Sans incrustada (acentos, ñ, ¿¡, €). Las correcciones hechas en la pantalla OCR se usan en el PDF, la búsqueda y las exportaciones.
- **Exportar texto** como **.txt** (UTF-8) o **Word (.docx)**, desde Exportar o desde la pantalla OCR.
- **Detección robusta**: segmentación por color (Cb/Cr) insensible a sombras duras y madera clara, cuadriláteros envolventes que recuperan esquinas tapadas o redondeadas, cuadernos abiertos (ambas páginas) y documentos que se salen del encuadre (el lado queda sobre el borde de la foto).
- **Encuadre IA** (Ajustes › Escaneo, activado por defecto): la red DocAligner lcnet100 (DocsaidLab, Apache-2.0, 4,8 MB
  en el APK, `imaging/NeuralCorners`) ARBITRA el recorte del detector clásico en la captura y en Editar › Recortar ›
  Auto-detectar (`DocumentDetector.neuralArbitrate`: si coinciden manda el clásico; si discrepan y la red está segura,
  la red; veto en fotos sin documento). En la vista en vivo corre cada ~300 ms (suavizado One Euro): contorno verde si red
  y clásico coinciden, ámbar si discrepan (la autocaptura sólo dispara si coinciden o con el clásico muy seguro). Usa
  el ONNX Runtime que llega con Indra: mientras no esté descargado, el detector funciona exactamente como antes.
  Banco (754 fotos + 99 negativos): fallo de detección 38,3 → 16,3 %; exámenes del usuario sin cambio (IoU 0,947).
- **Exportación**: PDF (A4/Carta/Oficio/Auto), imágenes JPG/PNG/WEBP en alta definición.
- **Compresor de PDF** integrado (niveles o tamaño objetivo), también desde "Compartir/Abrir con".
- **Optimizado para gama baja**: el trabajo se escala según la RAM y núcleos del dispositivo.
- **Integración con otras apps**: aparece en «Adjuntar» y «Cámara» de otras apps; escaneas y el documento vuelve solo a la app que lo pidió (ver abajo).

## Integración con otras apps
Cualquier app puede usar el escáner sin implementar nada de escaneo, como si fuera una función suya. Lo atiende
`ScanForResultActivity` (tarea de quien llama, `taskAffinity=""`, fuera de Recientes; no toca la pila de la app
principal).

**Modo directo (por defecto)**: se abre la cámara del escáner (captura automática, recorte y filtro como siempre,
calidad de Ajustes) → el usuario pulsa **«Adjuntar (N)»** (o, si solo se admite una página, basta con la primera
foto) → indicador breve «Adjuntando…» → el archivo se devuelve con `setResult` y el escáner se cierra, volviendo a la
otra app. Sin pantalla de revisión ni de exportación. Retoques opcionales: tocar la miniatura del lote en la cámara
abre la revisión (recortar, girar, filtro, añadir/quitar páginas, «Adjuntar a <app>»). Si la generación falla o se
cancela el indicador, también se pasa a la revisión, sin perder las páginas.

**Con revisión**: cámara → revisión → «Adjuntar a <app>». Para la acción `SCAN`, con el extra `REVIEW=true`; para
`GET_CONTENT`/`IMAGE_CAPTURE`, con el ajuste «Revisar antes de adjuntar» (Ajustes → Integración con otras apps;
desactivado por defecto).

| Petición | Qué devuelve |
|---|---|
| `ACTION_GET_CONTENT` `application/pdf` (o `application/*`) | un PDF con todas las páginas |
| `ACTION_GET_CONTENT` `image/*` / `image/jpeg` / `image/png` | una imagen (JPG/PNG); si hay varias páginas, el usuario elige cuál |
| … con `EXTRA_ALLOW_MULTIPLE` y formato imagen | una imagen por página en `ClipData` |
| `ACTION_GET_CONTENT` `*/*` (cualquier archivo) | PDF por defecto (Ajustes → Integración con otras apps: PDF o JPG) |
| `MediaStore.ACTION_IMAGE_CAPTURE` con `EXTRA_OUTPUT` (`content://`) | JPEG escrito en ese Uri (resultado sin datos) |
| `MediaStore.ACTION_IMAGE_CAPTURE` sin `EXTRA_OUTPUT` | miniatura `Bitmap` en el extra `"data"` |
| `com.scannerpromax.action.SCAN` | ver contrato abajo |
| `com.scannerpromax.action.EDIT` | reabre un escaneo ya entregado (ver abajo) |

Calidad y tamaño de página: los de Ajustes → Exportación. Los archivos se guardan en la caché propia
(`cache/attach/`, se borran a las 24 h) y se exponen con `FileProvider` + `FLAG_GRANT_READ_URI_PERMISSION`.
Por defecto el escaneo queda también en Documentos (interruptor en Ajustes). Por seguridad no se acepta `EXTRA_OUTPUT`
`file://` ni un Uri del propio proveedor de la app.

### Acción propia `com.scannerpromax.action.SCAN`
Extras opcionales (clave completa o alias corto; un valor desconocido se ignora):

| Extra | Alias | Tipo | Valores | Por defecto |
|---|---|---|---|---|
| `com.scannerpromax.extra.MODE` | `mode` | String | `document`, `book`, `id`, `receipt`, `whiteboard`, `photo` | `document` |
| `com.scannerpromax.extra.FORMAT` | `format` | String | `pdf`, `jpeg` (`jpg`), `png` | `pdf` |
| `com.scannerpromax.extra.MULTI_PAGE` | `multiPage` | Boolean | varias páginas (en imagen: un archivo por página) | `true` |
| `com.scannerpromax.extra.FILTER` | `filter` | String | `enhanced` (texto resaltado), `bw`, `original` | el de Ajustes |
| `com.scannerpromax.extra.OCR` | `ocr` | Boolean | texto reconocido en `EXTRA_TEXT` y PDF buscable | `false` |
| `com.scannerpromax.extra.TITLE` | `title` | String | nombre del archivo (máx. 100) | título automático |
| `com.scannerpromax.extra.REVIEW` | `review` | Boolean | `true` = pantalla de revisión antes de adjuntar | `false` (directo) |
| `com.scannerpromax.extra.CALLER_LABEL` | `callerLabel` | String | nombre de tu app para los textos («Adjuntando a …»), si el sistema no lo da por `callingPackage` (máx. 40) | nombre del sistema |

Los Boolean aceptan también texto (`"true"`/`"false"`/`"1"`/`"0"`) o números. Con `MULTI_PAGE=false` la primera
captura se entrega directamente (en modo `id`, tras el reverso u «Omitir»).

Resultado (`RESULT_OK`): `data` = Uri `content://` del primer archivo, `clipData` = todos, `type` = MIME, y los extras
`com.scannerpromax.extra.URIS` (`ArrayList<Uri>`), `…MIME_TYPE`, `…PAGE_COUNT` (Int), `…FILE_COUNT` (Int),
`…RESULT_FORMAT` (`pdf`/`jpg`/`png`), `com.scannerpromax.extra.DOCUMENT_ID` (String: id estable del documento en la
biblioteca del escáner, para editarlo después con `EDIT`) y, con `ocr`, `Intent.EXTRA_TEXT`. Cancelar →
`RESULT_CANCELED`. Con `SCAN` el escaneo se guarda siempre en Documentos (aunque el ajuste «Guardar también una copia»
esté apagado) para que `DOCUMENT_ID` sirva; con `GET_CONTENT` solo viaja si el ajuste lo guarda.
El permiso de lectura es temporal: copia el archivo a tu almacenamiento si lo necesitas más tarde.

```kotlin
private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
    if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
    val data = res.data ?: return@registerForActivityResult
    val clip = data.clipData
    val uris = if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri } else listOfNotNull(data.data)
    val pages = data.getIntExtra("com.scannerpromax.extra.PAGE_COUNT", uris.size)
    val text = data.getStringExtra(Intent.EXTRA_TEXT) // solo si se pidió ocr
    uris.forEach { uri -> contentResolver.openInputStream(uri)?.use { /* copiar o subir */ } }
}

fun escanearFactura() {
    val intent = Intent("com.scannerpromax.action.SCAN")
        .putExtra("com.scannerpromax.extra.MODE", "receipt")
        .putExtra("com.scannerpromax.extra.FORMAT", "pdf")
        .putExtra("com.scannerpromax.extra.OCR", true)
        .putExtra("com.scannerpromax.extra.TITLE", "Factura")
        .putExtra("com.scannerpromax.extra.CALLER_LABEL", "Mi app") // opcional
        // .putExtra("com.scannerpromax.extra.REVIEW", true)        // opcional: revisar antes de adjuntar
    try { scan.launch(intent) } catch (e: ActivityNotFoundException) { /* escáner no instalado */ }
}
```
En Android 11+ declara en tu manifiesto la visibilidad del escáner si quieres comprobar antes si está instalado:
`<queries><intent><action android:name="com.scannerpromax.action.SCAN" /></intent></queries>`.

### Acción `com.scannerpromax.action.EDIT` (editar un escaneo ya adjuntado)
Petición: `Intent("com.scannerpromax.action.EDIT")` con `com.scannerpromax.extra.DOCUMENT_ID` (alias `documentId`,
obligatorio: el que devolvió `SCAN`) y, opcionalmente, los mismos extras de formato que `SCAN` (`FORMAT`, `MULTI_PAGE`,
`OCR`, `TITLE`, `CALLER_LABEL`; `FILTER` se ignora para no pisar los retoques). Lánzala con
`startActivityForResult`/`StartActivityForResult()`.

Se abre ESE documento directamente en su pantalla de páginas (la misma de la app): tocar una página → visor → Editar
(recortar, girar, filtro, borrar manchas), mantener y arrastrar para reordenar, seleccionar y quitar páginas, «Añadir»
con la cámara o la galería y «Filtro» para todas. Arriba «‹ Cancelar»; abajo **«Guardar en <app>»**.

- Guardar → `RESULT_OK` con el **mismo formato de resultado que `SCAN`** (`data`, `clipData` con
  `FLAG_GRANT_READ_URI_PERMISSION`, `URIS`, `MIME_TYPE`, `PAGE_COUNT`, `FILE_COUNT`, `RESULT_FORMAT` y el mismo
  `DOCUMENT_ID`). Sustituye tu copia del archivo por la nueva.
- Cancelar/Atrás → `RESULT_CANCELED` (si hubo cambios se pregunta antes; quedan guardados en el escáner).
- Id inexistente (documento borrado, app reinstalada) o inválido → `RESULT_CANCELED` con
  `com.scannerpromax.extra.ERROR` = `document_not_found` / `invalid_document_id` y un aviso al usuario. Ofrece escanear
  de nuevo con `SCAN`.

```kotlin
val edit = Intent("com.scannerpromax.action.EDIT")
    .putExtra("com.scannerpromax.extra.DOCUMENT_ID", idGuardado)
    .putExtra("com.scannerpromax.extra.FORMAT", "pdf")
    .putExtra("com.scannerpromax.extra.CALLER_LABEL", "Mi app")
editar.launch(edit) // en el callback: RESULT_OK → reemplazar el archivo; ERROR=document_not_found → avisar
```

Desde adb: `adb shell am start -a com.scannerpromax.action.SCAN --es mode book --es format jpeg` (con revisión:
`--ez review true`). Editar: `adb shell am start -a com.scannerpromax.action.EDIT --es documentId <id>`.

## Reconocimiento de texto (Indra)
- **Indra** (Datalab, `ocr/indra/`, ONNX Runtime en el procesador) es el ÚNICO OCR de la app: detector de líneas,
  reconocedor, orden de lectura, orientación y modelo de lenguaje para las líneas dudosas (solo latín).
- Sus pesos (24,7 MB) y ONNX Runtime (`libonnxruntime.so`, ~13 MB para arm64) **no van en el APK**: se descargan solos en
  el primer arranque (`ocr/indra/IndraProvisioner`, WorkManager con notificación silenciosa), respetando *Descargas solo
  con Wi-Fi*. Sin red, las páginas quedan **pendientes** y se reconocen solas al terminar la descarga.
- Cada página se reconoce en segundo plano al añadirse (Texto en vivo, búsqueda y PDF buscable).
- **Maquetación de páginas** (opcional, Ajustes › Reconocimiento): PP-DocLayoutV3 + PP-LCNet doc_ori (137 MB) endereza
  las páginas giradas y monta la maquetación por partes (regiones + líneas de Indra) para el PDF reconstruido.
- La **IA en la nube** (DeepSeek / Claude Haiku con la clave del usuario) ya no es un motor de reconocimiento: se usa para la
  reconstrucción con IA.

## Dependencias y licencias (componentes de reconocimiento e IA)
| Componente | Uso | Licencia | Cómo llega |
|---|---|---|---|
| Indra OCR (datalab-to/indra) — código portado a Kotlin | OCR | Apache-2.0 | En el APK (`ocr/indra/`) |
| Indra OCR — pesos v1.1.0 (`datalab-to/indra-ocr`, latín) | OCR | OpenRAIL-M modificada (Datalab): gratis para uso personal/investigación y empresas < 20 M$; no para productos que compitan con Datalab. **Uso personal**: una distribución comercial necesitaría licencia de Datalab y trasladar sus restricciones de uso | Descarga automática (24,7 MB) |
| ONNX Runtime 1.31 (Microsoft) | Motor de Indra y de la maquetación | MIT | API Java + JNI en el APK; `libonnxruntime.so` se descarga (~13 MB) |
| PP-DocLayoutV3 + PP-LCNet doc_ori (PaddlePaddle, ONNX) | Maquetación por partes y orientación (opcional) | Apache-2.0 | Descarga opcional (137 MB) |
| OpenCV 4.10 | Imagen | Apache-2.0 | En el APK |
| PdfBox-Android | PDF | Apache-2.0 | En el APK |
| SDK de Anthropic para Java / OkHttp | IA en la nube (opcional, clave del usuario) | MIT / Apache-2.0 | En el APK |
| FSRCNN (super-resolución) | Imagen | Apache-2.0 | En el APK (`assets/models/`) |
| DocAligner lcnet100 (DocsaidLab / Ze Yuan, github.com/DocsaidLab/DocAligner, v1.1.1) | Encuadre IA (árbitro del recorte) | Apache-2.0 (aviso en `assets/models/README-DocAligner.txt`) | En el APK (`assets/models/docaligner_lcnet100.onnx`, 4,8 MB; usa el ONNX Runtime de Indra) |
| Inter, Liberation Sans y fuentes del PDF reconstruido | Interfaz / PDF | OFL-1.1 | En el APK |

Eliminados: ML Kit Text Recognition, PaddleOCR-VL-1.5 ("Lector de documentos"), Qwen3.5-0.8B ("Asistente visual") y
llama.cpp (submódulo `third_party/llama.cpp` y JNI `app/src/main/cpp`).

## Compilar
Requisitos: JDK 17+, Android SDK 35 (ya no hace falta el NDK ni CMake).
```
./gradlew :app:assembleRelease   # APKs por ABI en app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest
```

## Arquitectura
`imaging/` (OpenCV: detección, perspectiva, filtros, limpieza) · `data/` (documentos locales, ajustes) · `ocr/` · `pdf/` (exportación y compresión con PdfBox) · `export/` · `ui/` (Jetpack Compose).

## Rendimiento en gama baja
Objetivo de referencia: Android 8-10, 2 GB de RAM, 4× Cortex-A53, GPU débil. Interfaz a 60 fps y sin OOM.

- **Arranque**: `Application.onCreate` ya no carga nada pesado. OpenCV, PdfBox, los ajustes y la lista de documentos se
  cargan en un hilo de arranque (`ScannerApp.warmUp`); el splash se mantiene (`setKeepOnScreenCondition`) hasta que
  `ScannerApp.ready` es true y la navegación no se compone antes, así el hilo principal nunca espera. Las dependencias
  de `AppContainer` son `lazy` sincronizadas (`NativeLibs.ensureOpenCv()` / `ensurePdfBox()` garantizan el orden).
- **Perfil de referencia**: `app/src/main/baseline-prof.txt` (reglas para `com/scannerpromax/**`, Compose, listas
  perezosas, Coil, CameraX, OpenCV) + `androidx.profileinstaller`: ART precompila las rutas calientes al instalar.
- **Release**: R8 completo + recorte de recursos, solo idiomas `es`/`en`, logs `v`/`d` eliminados, APKs por ABI
  (instala el de tu ABI; `-PuniversalApk=false` evita generar el universal). `<profileable>` permite medir con
  Macrobenchmark/Perfetto.
- **Compose**: strong skipping (por defecto en Kotlin 2.0.21) + `app/compose_compiler_config.conf` (domain.* y `File`
  estables); degradados de marca creados una vez por tema; listas con `key`/`contentType`; acciones de tarjetas
  recordadas; búsqueda de Inicio (título + texto OCR) en segundo plano con debounce; comprobaciones de archivos de las
  miniaturas de Revisión fuera del hilo principal.
- **Miniaturas**: `ImageLoader` global de Coil (caché ≈15 % del heap y RGB_565 en gama baja, fundido corto, sin cabeceras
  HTTP); `AsyncImage` con tamaño acotado y precisión INEXACT en vez de `SubcomposeAsyncImage` (sin subcomposición por
  celda).
- **Editor**: vista previa progresiva (primero 480 px en gama baja sobre una geometría recortada en caché, luego refinado
  a la resolución de la pantalla solo cuando se deja de mover el control; los trabajos obsoletos se cancelan), miniaturas de filtros de
  una en una en un hilo de prioridad baja que cede el paso a la vista previa, arrastre de esquinas y borrador dibujados
  solo en la fase de dibujo (sin recomponer), lupa que dibuja una región del bitmap ya cargado con rutas reutilizadas.
- **Animaciones adaptativas** (`LocalPerf`): en gama baja (poca RAM o ≤4 núcleos) sin halos infinitos, sombras más
  cortas y transiciones más breves; con "Quitar animaciones" del sistema, sin animaciones.
- **Memoria**: `onTrimMemory` libera la caché de vista previa de OpenCV y, en segundo plano, las miniaturas de Coil;
  el editor recicla todos sus bitmaps al salir.
- **Diagnóstico**: en builds debug, StrictMode registra en Logcat los accesos a disco/red en el hilo principal y fugas.

## Calidad de imagen (resolución completa de principio a fin)
La imagen se capta y se procesa ENTERA; solo se reduce al exportar, según la calidad elegida.

| Etapa | Resolución / formato |
|---|---|
| Captura (`CameraEngine`) | Mayor tamaño 4:3 del modo normal de la cámara, hasta ~17 MP (S24 Ultra: 4000x3000, modo agrupado de 12 MP del sensor de 200 MP; sensores 48-64 MP: su modo agrupado de 12-16 MP). `CAPTURE_MODE_MAXIMIZE_QUALITY` y JPEG 100 en todos los equipos. Sin ViewPort: la foto no se recorta al aspecto de la vista previa. Si combinar con el análisis en vivo rebajara la foto por debajo del 75 % de su máximo, se prioriza la captura (sin detección en vivo). |
| Original (`original/`) | El JPEG de la cámara o de la galería se MUEVE tal cual (sin reescalar ni recomprimir; EXIF conservado y respetado al decodificar). Solo los originales generados (DNI compuesto, páginas de libro, fusión poca luz) se codifican, una vez, a JPEG 100. |
| Procesado (`processed/`) | Resolución completa del recorte dentro de `DeviceProfiler.maxWorkingPixels`: 20 MP gama alta (≥ 7 GB), 16 MP alta ligera, 12.6 MP media (cabe una foto de 12 MP entera), 8 MP gama baja (mínimo para documentos; Android 7 se acota por el heap grande, ≥ 4 MP). JPEG 95 (una sola codificación con pérdida) o PNG para B/N. Si un render se queda sin memoria se reintenta con el 60 % de píxeles (≥ 4 MP). |
| Miniaturas (`thumb/`) | 960 px de lado largo, JPEG 90, reducidas por mitades (sin aliasing); Coil las decodifica al tamaño real de la celda. |
| Editor | Vista previa refinada acorde a la pantalla (900-1280 px gama baja, 1200-2048 px resto); zoom hasta 5x que rehace la vista previa hasta 2048/4096 px. Imagen de trabajo: foto completa (≤ 12.6 MP) en gama alta, 8 MP media, 3 MP baja. |
| Visor (Revisión → lupa) | Pantalla completa con zoom 5x: base a resolución de pantalla y, al ampliar, la zona visible a resolución completa con `BitmapRegionDecoder`. |
| Exportación | Pequeño 1600 px (≈140 ppp A4, JPEG 60) · Equilibrado 2480 px (≈210 ppp) · Alta 3508 px (300 ppp A4, JPEG 92, por defecto) · Máxima (HD) sin límite: el JPEG procesado se incrusta/copia tal cual. |

WEBP no se usa para el procesado: el PDF no puede incrustarlo (habría que recodificar) y su codificación es más lenta;
en B/N el PNG va a 1 bit en el PDF de todos modos.

## Algoritmos
Todo en `imaging/` (OpenCV 4.10, sin red). Tiempos medidos con el harness Python/OpenCV 4.10 equivalente
(PC, 1 hilo; un Cortex-A53 a 1.4 GHz es ~6-8x más lento):

| Filtro | Vista previa 1000 px | Final 2 MP | Final 8 MP |
|---|---|---|---|
| Mágico | ~28 ms | ~75-90 ms | ~450-540 ms |
| Mágico Pro (con SR x2 si la fuente es pequeña) | ~27 ms | ~105 ms | ~450-600 ms (sin SR) |
| Recibo (AUTO) | ~19 ms | ~35 ms | ~135 ms |
| Clasificación AUTO | ~5 ms | ~9 ms | ~25 ms |

- **Auto inteligente** (`FilterType.AUTO`, `ImageEnhancer.analyzeContent/recommendFilter`): estadísticas a 256 px
  (fracción de papel, tinta, color, color de la tinta, nivel del papel, tinta más oscura, aspecto) + detección de
  muaré por picos aislados en el espectro de la crominancia. Clases: texto, texto con color, foto, recibo térmico,
  pizarra, poca luz y foto de pantalla; cada una con su procesamiento (recibo: contraste local fuerte con CLAHE;
  pantalla: anti-muaré antes del filtro). `recommendFilter` devuelve el filtro concreto equivalente (data/ puede usarlo
  para el filtro inicial; asignar `AUTO` directamente es preferible).
- **Sombras tratadas por zona** (`ShadowMap.kt`: `Shadows`, `ShadowMath`): modelo multiplicativo (la sombra de la mano,
  del celular o de un pliegue multiplica la luz por un factor por canal, más azulado, suave dentro y con penumbra
  estrecha). La sombra se detecta **en el fondo estimado** (papel sin tinta), como la zona cuya luz cae por debajo
  de la envolvente de luz plena del entorno (fuerza 0..1 con margen para la penumbra; la tinta y los objetos no
  cuentan). Sólo en esa zona: (1) **re-iluminación** a ~640 px y por canal sobre la imagen ya normalizada (cierre
  morfológico que quita la tinta sin mover el borde de la sombra + media del papel sin sesgo; bloques oscuros o de
  color se rellenan con el entorno) -> sin escalón ni halo en la penumbra y sin tinte azul; (2) **umbrales por el
  ruido medido** en la zona (`TextRegions`: no por la ganancia, que lo sobrestimaba y partía los trazos tenues);
  (3) **des-ruido** de la zona (filtro guiado con el σ medido; `PrintedPage`) y (4) **color de la tinta cálida**
  (amarillo, naranja, marrón: la luz de la sombra y el ISP la apagan) reforzado según la luz (x luz^-0.8, máx. x1.6).
  Fuera de la sombra nada cambia. **Sombras profundas** (móvil o mano a pleno sol, papel al 15-40 % de la luz, que
  el fondo de baja resolución no llega a seguir): en el núcleo de una sombra que entra desde el borde de la imagen,
  lejos de bordes nítidos (el canto de una figura o foto impresa es un escalón de 1-2 px; la penumbra, una rampa), con
  texto dentro y en una zona extensa, el residuo puede bajar hasta 0.15 y el umbral de papel sigue al grano medido ->
  el texto de la sombra se lee como el de la zona iluminada (antes la zona salía en blanco o en gris oscuro). Las
  fotos en color (fracción alta de píxeles saturados), los bloques oscuros y los dibujos grises dentro de una sombra
  suave no se re-iluminan; el tinte del residuo se acota (±5 %) fuera de la penumbra y el refuerzo del color cálido no
  satura ningún canal (el rojo no gira a naranja). El fondo ajeno (mesa) se busca con el fondo ya re-iluminado: una
  sombra profunda en el borde no se toma por mesa. Banco v3 (RDD, Jung, OSR, RealDAE con referencia sin sombra):
  RDD MAGIC FM 76.7 -> 79.4, PSNR 13.3 -> 13.9, SSIM 0.733 -> 0.750. Sintético con sombra conocida (penumbra σ=14 px, tinte 0.45/0.50/0.62): error de
  la normalización en la penumbra 6.3 -> 3.8 niveles (núcleo 6.1 -> 4.2, suelo de ruido ~4); papel de *Texto
  resaltado* en la penumbra 1.4 -> 0.4.
- **Zonas alejadas borrosas e imagen doble por movimiento** (`Deblur.kt`/`DeblurMath.kt`, `Deghost.kt`/`DeghostMath.kt`;
  `PageProcessor.restoreSharpness`, sólo en el render final con recorte y fuera de la gama baja): tras rectificar, la
  parte alejada de la hoja queda ampliada y desenfocada. (1) **Fantasma/estela** (pulso de la mano): núcleo NO
  paramétrico estimado por zonas de 640 px con predicción bitonal (papel + tinta) y mínimos cuadrados, rechazado si
  toca el borde del soporte o los gradientes no tienen diversidad de orientación (fotos), y ecualización RELATIVA a
  las zonas más nítidas de la propia página; (2) **desenfoque** por zonas: escala local de la homografía + medida
  autocalibrada (desenfocando las teselas más nítidas), Wiener por franjas y clases, anti-halo. La clasificación
  impreso/cuaderno usa la página previa a la restauración. Banco (sin regresiones): pérdida de trazo MAGIC
  2.41 -> 2.32 %, B/N 2.68 -> 2.59 %; sintéticos con fantasma 23.1 -> 24.3 dB. `MultiFrameFusion.refineLocal`:
  alineación local por teselas tras la homografía (ráfaga sin fantasmas, +1.2 dB).
- **Detección de bordes** (`DocumentDetector`, `DetLines`): la confianza sale de la calidad del borde (apoyo global,
  peor lado, ángulos, tramos sobre el marco) y no del tamaño del documento, con una comprobación de papel (el interior
  no puede ser más oscuro que su entorno). Un cuadrilátero con papel a ambos lados y tinta fuera es un **marco o tabla
  impresa** de una página escaneada: se busca la hoja real o se usa la imagen completa. Si el mejor contorno tiene un
  borde pobre (hoja + mesa/póster) se prefiere un candidato interior nítido. En foto completa compiten además
  hipótesis por **rectas** (Hough agrupado en rectas casi paralelas). Banco SmartDoc/MIDV (84 fotos con esquinas
  verdaderas): fallo 44 % -> 23 %, error de esquina 10.3 -> 6.0 % de la diagonal, mismo tiempo (~70 ms).
- **Enrutado por tipo de página** (`PrintedPage.analyze` + `PrintClassifier`, filtros *Texto resaltado* y *Blanco y
  negro*, decidido antes de la super-resolución): sólo una **hoja de cuaderno con rayado claro** (≥ 10 rectas largas,
  claras y finas, lado ≥ 1000 px, sin tabla ni texto impreso denso y alineación de líneas base < 0.35: la letra impresa
  da 0.5-0.85, la manuscrita 0.02-0.35) va a los recuadros de escritura (`TextRegions`), que borran el rayado. Todo lo
  demás (impresos, tablas, formularios, recibos, tarjetas, pantallas, documentos antiguos, letra a mano sobre papel
  liso) usa el render tipo fotocopiadora (`PrintedPage`), que conserva todo el contenido; en MAGIC con punto negro
  adaptativo para tinta tenue. **Pizarras de tiza / modo oscuro** (`PrintPolarity`: trazos más claros que la mediana
  local frente a más oscuros, ≥ 2.6 en tiza, ≤ 1.2 en papel): se invierte la luminancia antes de procesar y la tiza
  sale como tinta oscura sobre blanco (`TextRegions` tiene además su propia comprobación de polaridad).
- **Tipo de página y tono de impresos** (`PrintClassifier.pageType`, `PrintTone`, `PrintedPage`): cada página se
  clasifica como cuaderno, manuscrito, formulario/tabla, recibo (tique alto y estrecho), con imágenes (fotos o tramas
  interiores >= 6 %, sin contar rellenos negros macizos ni manchas pegadas al borde) o texto impreso. En *Blanco y
  negro* el gris que se binariza es la **luminancia** en los trazos (la tinta azul, roja o naranja fina queda tan
  oscura como se ve: recibos de tinta azul, tinta roja desvaída, texto de color de revistas) y la mezcla con el canal
  máximo sólo en los **bloques** de color (más gruesos que ~0.45 alturas de letra), que siguen saliendo claros; en
  páginas con imágenes las fotos interiores se conservan en gris. El punto negro se mide sin el **fondo ajeno** del
  recorte (bandas finas de mesa y fondos oscuros lisos y profundos: un bloque oscuro con texto claro dentro es
  contenido) y, si ese fondo ocupa > 15 %, el papel y su ruido se vuelven a medir sin él (libro sobre fondo negro sin
  recorte: el texto ya no sale lavado). La tinta tenue se lleva hacia el negro también en B/N. Con **luz de color**
  cuyo tono comparte la tinta, *Texto resaltado* apaga el color (la tinta salía verdosa o violeta).
- **Segmentación de la escritura** (`TextRegions`, filtros *Texto resaltado* y *Blanco y negro* y los antiguos
  que se muestran como ellos): mapa de tinta a resolución completa sobre la imagen sin sombras (oscuridad del canal
  máximo + croma de tinta, que separa el bolígrafo azul de la cuadrícula azul clara); borrado de la rejilla clara
  (rectas largas por aperturas a -9..9°, límite LOCAL respecto a la oscuridad de la rejilla del entorno: las líneas
  de un diagrama a bolígrafo se conservan); componentes conexas con histéresis relativa al ruido local (sombras);
  espiral/anillas y bordes oscuros -> zona en blanco; fotos y bloques de color -> recuadro IMAGEN (mejora suave);
  agrupación en palabras/líneas/bloques (`BoxGrouping`, con detección de texto girado 90°) con margen según la
  altura de letra. Render: fuera de los recuadros blanco puro; dentro sólo la tinta con alfa suave (sin halos) y
  contraste por zona (el lápiz claro se refuerza). **Trazos uniformes**: en los trazos firmes y finos la tinta se
  normaliza respecto del máximo local a escala de trazo (cierre de 3 px para los fallos de tinta), así los tramos de
  poca presión de una misma letra salen tan oscuros y continuos como los demás; en Texto resaltado se pintan con el
  color medio de la tinta del entorno e intensidad única. Las manchas tenues (transparencias, sombras) conservan el
  tono relativo. Sintético (bolígrafo/lápiz con presión 30-100 %): roturas por trazo 4.1 -> 1.3, variación del tono
  0.22 -> 0.04. **Papel texturado** (pergamino, papel viejo): la oscuridad se divide por la textura local del papel
  medida fuera de la tinta, así el papel no se une con la escritura. **Espiral** sólo si hay ≥ 8 anillas parecidas en
  banda estrecha y paso regular sin letras dentro (los renglones de letra gruesa ya no se borran). **Dos tintas**: una
  población de tinta débil bien separada (transparencia del reverso, manchas) se pinta en gris claro proporcional.
  DIBCO 2009-2019: F-measure B/N 43 -> 70. Estimaciones a <= 2000 px, render a resolución completa por
  franjas de 1 MP: 12 MP ~1.0-1.3 s y 20 MP ~1.5-1.7 s en PC monohilo. `TextRegions.detect(bitmap)` devuelve los
  recuadros (TEXT/IMAGE) para dibujarlos en la interfaz.
- **Cuadrícula conservada como RED REGULAR** (`RulingGrid.lattice`, `PageEdits.keepRuling`, interruptor "Conservar
  cuadrícula / renglones" en Limpieza, activado por defecto; v12): en franjas a lo largo de cada familia (H y V) se
  buscan los picos de la huella y del perfil de oscuridad completa (también las rectas en sombra), se ajusta el PASO y la
  FASE (crecimiento desde un ancla con el paso local: entre rectas vecinas casi no cambia) y cada recta recibe un índice
  entero k; las franjas con la misma fase se enlazan en zonas (una por página) con un modelo suave
  pos(k, t) = fase(t) + k·paso(t) + c·k² (renumeración ±1 de los tramos que no encajan). Sólo se aceptan rectas en
  posiciones de la red: las candidatas a medio paso (rayas inventadas), los duplicados y los trazos alineados se
  descartan; las que faltan se completan; si el paso es incoherente no se dibuja rejilla. Los restos de la rejilla
  original que la supresión no reconoció (tramos en sombra, entrecortados, rectas impresas más marcadas) se borran del
  mapa de tinta a lo largo de la red (límite por recta, tono de la rejilla, apertura de un paso; manchas enteras dentro
  de la banda). La rejilla se dibuja sólo en la zona de escritura, fina y limpia por debajo de la tinta (tono de la propia cuadrícula en *Texto resaltado*, gris
  claro en *Blanco y negro*), nunca sobre la espiral. En el PDF a 1 bit de B/N los grises claros aislados se traman
  (`BinaryDither`) para que la cuadrícula no desaparezca.
- **Borde de tinta limpio, sin halo** (v12): en *Texto resaltado* de páginas tipo impreso (`PrintedPage`) des-ruido
  bilateral de la luminancia y suavizado del color antes del realce, realce sin sobreimpulsos (acotado al entorno 3x3) y
  la cola del desenfoque de cada trazo sobre papel (< 30 % del máximo local) llevada a blanco con una rampa de 1-2 px; en
  B/N, des-ruido bilateral y realce acotado antes del umbral. En cuadernos (`TextRegions`) el borde de un trazo firme
  usa el mismo color que su centro (peso del modelo uniforme a escala de trazo), el alfa se suaviza y re-afila, los
  restos tenues no se refuerzan y la oscuridad por canal se acota sin cambiar el matiz. Métrica de halo (banco local):
  suciedad a 2.5-4.5 px del trazo, motas pegadas al borde y desvío de color del borde.
- **Bordes a blanco** (`EdgeClean`, *Texto resaltado* y *Blanco y negro*, cuadernos e impresos): lo que queda junto al
  borde y no es contenido (franjas del canto, manchas macizas, cuñas de esquina, rayas del canto con fondo ajeno entre
  ellas y el borde, restos rodeados de mesa, motas aisladas y, en cuadernos, lo que queda fuera de la cuadrícula) pasa
  a blanco. Se conservan las letras cortadas por el borde, los renglones pegados a él (un renglón de letra fina se
  distingue de una raya porque sus trazos verticales tienen la altura de la letra), los bloques con letras dentro
  (bocadillos, botones) y, en los lados que son cantos de la hoja (`sheetSides`), todo salvo las franjas finas.
- **Tinta de color desvaída en B/N** (`Cv.darkenColorStrokes`): los trazos finos rojos o verdes se oscurecen cuando la
  tinta oscura es neutra y el papel también (sin motas en pósits de color).
- **Hoja curvada** (`GridDewarp`, `PageEdits.autoDewarp`, tras la perspectiva y antes del filtro): supone que cada
  línea de la tabla/cuadrícula es recta y horizontal o vertical en la hoja real. A ≤ 1600 px: tinta relativa al fondo
  local en el canal mínimo (tinta negra y cuadrícula azul clara por igual), aperturas con segmentos largos a -12°/0°/12°,
  centros de trazo por columnas/filas, **seguimiento** con predicción de pendiente y **enlace** de tramos colineales a
  través del texto; cada cadena se depura (regresión local robusta + cuadrática) y se descarta si no es tinta fina
  (los centros de renglón de texto no pasan). Sin líneas suficientes se usan **guías del propio texto** (`TextGuides`),
  que entran como familias horizontal y vertical en el mismo ajuste:
  - **Renglones**: dos líneas por renglón, la **base** y la **línea media** (altura de la x). Se miden con perfiles de
    tinta en ventanas de ±2 letras a lo largo del renglón, enderezadas con su centro: el mayor salto de densidad del
    cuerpo de las letras, que no mueven las ascendentes, las mayúsculas ni las descendentes. La línea media sólo se usa
    donde su distancia a la base es la del renglón (las cifras y las mayúsculas no tienen altura de x). Como ambas deben
    quedar rectas, la separación constante corrige el escalado vertical local. Cada línea se depura con una regresión
    robusta y se suaviza con una regresión local cuadrática. Las palabras cortas de los extremos que el seguimiento
    dejó fuera se añaden al renglón.
  - **Guías verticales**: cada renglón se corta en tramos por los huecos de más de 2.5 letras (calles entre columnas,
    tabuladores). Los inicios de tramo de renglones consecutivos se enlazan cuando caen sobre una curva suave
    (predicción con la pendiente reciente; se saltan renglones con sangría, viñetas, títulos centrados y renglones
    cortos). Así salen el **margen izquierdo**, los **bordes de columna** y los tabuladores repetidos. Los finales
    sólo cuentan si el texto está **justificado**: al menos el 65 % de los renglones de su zona acaban en la guía,
    algo que no ocurre con texto en bandera. Una guía necesita al menos 6 renglones y un soporte de al menos el 60 %
    de los renglones de su zona; además, la mediana de la distancia a la guía de todos los inicios cercanos debe ser
    pequeña (una racha casual de inicios parecidos en letra a mano muy dispersa no es un margen), y se suaviza con una regresión local cuadrática. Su peso depende del número de
    renglones, de la dispersión y del soporte. Con inicios dispersos (manuscrito: más de 0.07 letras + 0.5 px) la
    guía es **amplia**: tolerancia mayor, peso 0.35, y no cuenta para decidir si la hoja es plana. Por encima de 0.4
    letras de dispersión no se usa.
  - **Decisión**: renglones y guías verticales se juzgan por separado. Basta con que un grupo esté curvado (por
    ejemplo, un margen combado con renglones rectos), pero el grupo que ya era recto no puede torcerse. La curvatura de
    una guía por debajo de su ruido no cuenta. Con 30 renglones o más y curvatura clara (≥ 1.6 veces el umbral de hoja plana) basta una mejora del 30 % (pliegues fuertes:
    residuo hasta el 15 % del inicial). Si las guías verticales no encajan, se ajusta sólo con los renglones. Como último respaldo (texto muy
    pequeño o renglones partidos por un pliegue) se usa la línea base de los pies de las letras.
  - **Ángulo**: si no se aplica el enderezado curvo, el giro se mide con las pendientes de los renglones (mediana
    ponderada, dispersión ≤ 0.35°) y de las guías verticales (deben coincidir en ±0.8°). Si eso no es coherente, se
    recurre a los perfiles de proyección (`Cleanup.estimateSkew`).
  - **Cantos de la hoja** (`DwBorder`, hojas curvadas, dobladas y arrugadas): en la hoja real los cuatro cantos son
    rectos; en la foto son curvas o quebradas (cada pliegue cambia la pendiente del canto) y el recorte recto deja fuera
    lo que se sale de él o dentro cuñas de la mesa. Sobre el plano rectificado AMPLIADO (un 7 % por lado, tomado de la
    foto) se buscan los cantos con perfiles transversales a ≤ 800 px de la imagen sin texto (cierre) en Lab: contraste
    entre ambos lados por lo "papel" del lado interior (luminosidad y croma del papel de la hoja) y penalizado si más
    allá vuelve a haber papel (el borde de una cabecera de color o de una foto pegada al canto no es el canto);
    programación dinámica con pendiente acotada; un lado se acepta con apoyo ≥ 60 %, si no deja fuera escritura y si no
    corta ninguna línea de la hoja (un renglón o una regla que sigue más allá del "canto" indica una sombra o un rizo).
    Los cantos entran en el mismo ajuste que las líneas como líneas con POSICIÓN impuesta (superior en v = 0, izquierdo
    en u = 0...); los lados sin canto fiable quedan en el lado del recorte. La salida es exactamente la hoja: lo que se
    salía del recorte se recupera de la foto en el mismo `remap` y la mesa queda fuera. Se usa si algún canto se aparta
    ≥ 4 px (a 1600 px) de su recta o del recorte, las líneas de la hoja no quedan más torcidas, los cantos encajan
    (≤ 3 px) y el jacobiano está en 0.5..2; si falla, se reintenta sin el canto más dudoso. Las tablas y cuadrículas
    densas ya enderezadas por sus líneas (≥ 10 + 10) no se tocan. Los lados que son canto se comunican a los filtros
    (`Model.sheetSides` -> `ImageEnhancer.Options.sheetSides`): allí no hay mesa, así que la limpieza del fondo pegado
    al borde de `PrintedPage` no borra lo que toca esos lados (cabecera de color o foto a sangre); en los lados que
    siguen en el recorte se limpia como siempre. Banco (manifest_v2 frente a la base e92c03d, MS-SSIM / LD): DIR300
    0.441 -> 0.473 / 15.4 -> 12.8 px, UVDoc 0.379 -> 0.425 / 21.3 -> 17.9, WarpDoc 0.273 -> 0.287 / 26.4 -> 25.5,
    Inv3DReal doblada 0.456 -> 0.462 / 13.0 -> 12.3 (allí manda el recorte); OCR "Texto resaltado" hoja curvada
    CER 28.2 -> 27.4 %; fotos de cuaderno y tabla sin cambios. Coste: geometría +20 % (render completo +12 %).
  - **Pliegues** (aristas rectas por trozos): un pliegue es una arista donde la hoja cambia de pendiente; la placa
    delgada (curvatura al cuadrado) la reparte en varias celdas y deja los renglones ondulados a ambos lados. Tras el
    primer ajuste se repite dos veces con la placa delgada reponderada (tipo Huber) en los nodos cuya segunda diferencia
    supera 3 veces la mediana (y ≥ 1 px a 1600 px): la curvatura se concentra en la arista y el resto queda casi plano
    por trozos. No en mallas densas (cuadrícula de cuaderno, tabla): sus líneas ya fijan el campo.
  Modelo: campo directo suave (u, v) = F(x, y) en una rejilla bilineal de ~28 celdas por mínimos cuadrados en banda
  (v constante a lo largo de cada horizontal, u a lo largo de cada vertical, placa delgada, Cauchy-Riemann débil y ancla
  débil a la identidad), descarte iterativo de líneas y tramos atípicos (subrayados, trazos de escritura) y, en
  cuadrículas **regulares** (cuaderno), igualado del paso conservando la extensión de cada corrida (las celdas que la
  curvatura comprime junto a la espiral recuperan su ancho; las tablas de columnas desiguales sólo se enderezan). Las dos
  páginas de un cuaderno abierto se tratan como grupos de líneas distintos. Seguridad: no se aplica si la hoja ya es plana
  (percentil 90 de la desviación de las líneas < 1.4 px a 1600 px: el giro lo resuelve el enderezado normal, que se
  omite cuando el curvo se aplica), si las líneas no quedan al menos un 45 % más rectas o si el jacobiano sale de
  0.45..2.2. Si el campo empuja contenido de la hoja (escritura junto al borde) fuera del lienzo, el lienzo se **amplía** lo
  justo (el fondo que no es hoja se pinta del color del papel); lo que cae fuera del recorte también va del color del
  papel. **Cuñas del fondo**: el recorte es un cuadrilátero recto y la hoja curvada tiene bordes curvos;
  las cuñas de fondo texturado o de otro tono (hierba, mesa, dedos) que quedan dentro del recorte junto al borde
  (franja que se estrecha, 4-35 % del lado) se pintan del color del papel (`Cleanup.fillEdgeWedges`); las fotos o
  bandas de ancho constante pegadas al borde se conservan. El enderezado normal gira con el lienzo ampliado (no recorta las esquinas). El campo se invierte (Newton) en una rejilla de salida de 8 px y se guarda normalizado: vista previa, base de
  los trazos de borrado y render final usan el mismo modelo (caché por recorte, rotación y firma de la página: 16x16 grises y proporción, para que dos fotos parecidas sin recorte no compartan modelo). El render
  compone perspectiva + rotación + hoja curvada en un **único `remap` cúbico** desde el original, por franjas de 1 MP.
  Sintético (2000x2700, desviación máx. de las líneas antes -> después): libro 20.2 -> 2.8 px (tabla) y 23.8 -> 1.8 px
  (cuaderno, error de posición 24.1 -> 0.6 px rms), ondulación 18.2 -> 2.3/4.0, combado diagonal 4.6 -> 1.5/1.8, esquina
  doblada 12.4 -> 1.2 / 14.9 -> 3.3, texto sin líneas 11.3 -> 1.7 (libro); hojas planas intactas. PC monohilo:
  estimación ~150-350 ms, remap compuesto 8 MP ~370 ms (tabla real del S24 Ultra, 3000x4000 -> 2387x3529).
- **Iluminación**: fondo por cierre morfológico + mediana a 256 px (no desplaza los bordes); las sombras duras de la mano/celular (zonas oscuras lisas
  conectadas con el borde) se tratan como papel en vez de rellenarse; relleno de fotos/bloques por convolución
  normalizada (desenfoques grandes a resolución reducida) y afinado con **filtro guiado conjunto** a 512 px (320 px
  en vista previa) para que el borde de la sombra quede nítido. División por el fondo = sombras, viñeteo y degradados
  fuera y balance de blancos con los píxeles de papel. En una sombra dura sintética el error del papel bajó de 23.6 a
  6.3 niveles y en el borde de la sombra de 47.6 a 11.9.
- **Des-ruido**: filtro guiado de He auto-guiado sobre la luminancia (O(N) con `boxFilter`/`sqrBoxFilter`, por bandas:
  memoria acotada, 8 MP en ~90 ms en PC) + des-ruido cromático a media resolución, en una sola conversión YCrCb.
  Sustituye a NLM (≈3.3 s a 2 MP en PC) y al bilateral en todos los tiers.
- **Super-resolución x2** (Mágico Pro, render final, fuentes < 1600 px; gama baja hasta ~1 MP): FSRCNN (pesos de
  `Saafke/FSRCNN_Tensorflow`, Apache-2.0, re-empaquetados en ONNX en `assets/models/`, ver `README-FSRCNN.txt`) con
  `org.opencv.dnn` sobre el canal Y en mosaicos de 192 px (+8 de solape). +1.1..1.8 dB PSNR frente a bicúbico.
  Alternativa sin modelo: Lanczos + realce dirigido por bordes con anti-halo. `ImageEnhancer.init(context)` (o
  `SuperResolution.init`) da acceso a los assets.
- **Fusión multi-cuadro** (`MultiFrameFusion`, cámara: botón luna "Poca luz / anti-reflejos", automático con poca
  luz o reflejos): ráfaga de 3 (gama baja, ~3 MP) o 4 fotos; referencia = la más nítida; alineación ORB + homografía
  RANSAC a baja resolución (ECC euclídeo de respaldo); promedio robusto ponderado por diferencia (sin fantasmas) y
  mínimo de luminancia en los brillos especulares. Ruido del papel x1.9 menor con 4 cuadros; reflejo 251 -> 75 (real 71).
- **Muaré y recibos**: anti-muaré cromático fuerte (a/b a 1/4 de resolución) + paso bajo/realce de luminancia; recibos
  térmicos desvaídos: negro = tinta más oscura, gamma, CLAHE y nuevo punto blanco.
- **Cámara**: análisis en vivo sólo con el plano Y (YUV_420_888, 640x480), limitador de FPS adaptativo (el análisis
  ocupa ≤35 % de un núcleo en gama baja y cede CPU mientras se procesan capturas), estado de calidad que sólo emite
  cuando cambia algo visible, overlay dibujado en la fase de dibujo. OpenCV usa núcleos-1 hilos (la UI conserva uno).
