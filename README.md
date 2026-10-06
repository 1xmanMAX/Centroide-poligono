# ESCÁNER PRO MAX

Escáner de documentos para Android con mejora de imagen avanzada, OCR sin internet y herramientas PDF.

## Funciones
- **Cámara inteligente**: detección de bordes en vivo, autocaptura, multipágina, flash, enfoque al tocar.
- **Modos**: Documento, Libro (2 páginas → separadas), DNI/Tarjeta (anverso + reverso en una hoja A4 a tamaño real), Recibo, Pizarra, Foto.
- **Mejora "mágica"**: elimina sombras, normaliza la iluminación, blanquea el papel, realza la tinta; Mágico Pro para cámaras de baja calidad (des-ruido + ampliación); B/N adaptativo, Ahorro de tinta, Grises, Vívido, Pizarra.
- **Recorte preciso**: esquinas arrastrables con lupa, perspectiva corregida con relación de aspecto real, enderezado automático del texto.
- **Limpieza**: quita ruido y rayas automáticamente; borrador manual (reparación inteligente o pintar blanco) con deshacer/rehacer.
- **OCR** en el dispositivo (ML Kit) y **PDF con texto buscable**, contraseña opcional.
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
