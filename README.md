# ESCÁNER PRO MAX

Escáner de documentos para Android con mejora de imagen avanzada, OCR sin internet y herramientas PDF.

## Funciones
- **Cámara inteligente**: detección de bordes en vivo, autocaptura, multipágina, flash, enfoque al tocar.
- **Modos**: Documento, Libro (2 páginas → separadas), DNI/Tarjeta (anverso + reverso en una hoja A4 a tamaño real), Recibo, Pizarra, Foto.
- **Mejora "mágica"**: elimina sombras, normaliza la iluminación, blanquea el papel, realza la tinta; Mágico Pro para cámaras de baja calidad (des-ruido + ampliación); B/N adaptativo, Ahorro de tinta, Grises, Vívido, Pizarra.
- **Recorte preciso**: esquinas arrastrables con lupa, perspectiva corregida con relación de aspecto real, enderezado automático del texto.
- **Limpieza**: quita ruido y rayas automáticamente; borrador manual (reparación inteligente o pintar blanco) con deshacer/rehacer.
- **OCR** en el dispositivo (ML Kit) y **PDF con texto buscable**, contraseña opcional.
- **Texto en el PDF** (Ajustes → "Texto en el PDF", también en Exportar): *Solo imagen*, *Buscable* (capa invisible palabra a palabra), *Buscable + texto reconocido* (páginas legibles con el texto, tras cada página o al final) y *Solo texto*. Fuente Liberation Sans incrustada (acentos, ñ, ¿¡, €). Las correcciones hechas en la pantalla OCR se usan en el PDF, la búsqueda y las exportaciones.
- **Exportar texto** como **.txt** (UTF-8) o **Word (.docx)**, desde Exportar o desde la pantalla OCR.
- Filtro por defecto **Auto inteligente**: clasifica cada página (texto, color, foto, recibo, pizarra, poca luz, foto de pantalla) y aplica la mejora adecuada.
- **Exportación**: PDF (A4/Carta/Oficio/Auto), imágenes JPG/PNG/WEBP en alta definición.
- **Compresor de PDF** integrado (niveles o tamaño objetivo), también desde "Compartir/Abrir con".
- **Optimizado para gama baja**: el trabajo se escala según la RAM y núcleos del dispositivo.

## Compilar
Requisitos: JDK 17+, Android SDK 35.
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
  a 900-1200 px solo cuando se deja de mover el control; los trabajos obsoletos se cancelan), miniaturas de filtros de
  una en una en un hilo de prioridad baja que cede el paso a la vista previa, arrastre de esquinas y borrador dibujados
  solo en la fase de dibujo (sin recomponer), lupa que dibuja una región del bitmap ya cargado con rutas reutilizadas.
- **Animaciones adaptativas** (`LocalPerf`): en gama baja (poca RAM o ≤4 núcleos) sin halos infinitos, sombras más
  cortas y transiciones más breves; con "Quitar animaciones" del sistema, sin animaciones.
- **Memoria**: `onTrimMemory` libera la caché de vista previa de OpenCV y, en segundo plano, las miniaturas de Coil;
  el editor recicla todos sus bitmaps al salir.
- **Diagnóstico**: en builds debug, StrictMode registra en Logcat los accesos a disco/red en el hilo principal y fugas.

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
