# ESCÁNER PRO MAX

Escáner de documentos para Android con mejora de imagen avanzada, OCR sin internet y herramientas PDF.

## Funciones
- **Cámara inteligente**: detección de bordes en vivo, autocaptura, multipágina, flash, enfoque al tocar.
- **Modos**: Documento, Libro (2 páginas → separadas), DNI/Tarjeta (anverso + reverso en una hoja A4 a tamaño real), Recibo, Pizarra, Foto.
- **3 filtros**: *Blanco y negro* (texto negro nítido sobre blanco puro, sin sombras ni motas; quita la cuadrícula de color clara de los cuadernos conservando la escritura), *Texto resaltado* (por defecto: sin sombras, papel blanco neutro, tinta reforzada con su color; des-ruido, super-resolución y gamma para poca luz automáticos) y *Color original* (colores fieles, solo recorte y un toque de nitidez). Los filtros antiguos de documentos guardados se siguen renderizando y se muestran como su equivalente.
- **Recorte preciso**: esquinas arrastrables con lupa, perspectiva corregida con relación de aspecto real, enderezado automático del texto y **enderezado de hojas curvadas/combadas** usando las líneas de tablas, cuadrículas o renglones (interruptor "Enderezar hoja curvada" en Limpieza).
- **Limpieza**: quita ruido y rayas automáticamente; borrador manual (reparación inteligente o pintar blanco) con deshacer/rehacer.
- **OCR** en el dispositivo (ML Kit) y **PDF con texto buscable**, contraseña opcional.
- **Texto en el PDF** (Ajustes → "Texto en el PDF", también en Exportar): *Solo imagen*, *Buscable* (capa invisible palabra a palabra), *Buscable + texto reconocido* (páginas legibles con el texto, tras cada página o al final) y *Solo texto*. Fuente Liberation Sans incrustada (acentos, ñ, ¿¡, €). Las correcciones hechas en la pantalla OCR se usan en el PDF, la búsqueda y las exportaciones.
- **Exportar texto** como **.txt** (UTF-8) o **Word (.docx)**, desde Exportar o desde la pantalla OCR.
- **Detección robusta**: segmentación por color (Cb/Cr) insensible a sombras duras y madera clara, cuadriláteros envolventes que recuperan esquinas tapadas o redondeadas, cuadernos abiertos (ambas páginas) y documentos que se salen del encuadre (el lado queda sobre el borde de la foto).
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
