# PROGRESO — ESCÁNER PRO MAX

Registro vivo del estado del proyecto para poder retomar tras cualquier fallo o reinicio.
Actualízalo en cada paso importante (y haz commit + push).

## Estado actual (2026-10-10)
- Rama de trabajo: `ccr-4d2f0b52-3ktfkq` (se sube siempre a `origin`).
- Último APK: **v11 LISTA (2026-10-12)** — ronda v3 integrada en `ccr-4d2f0b52-3ktfkq`: a176448 desenfoque + fantasma (Deblur, Deghost, MultiFrameFusion.refineLocal; versión b2381df), 3c297fa sombras por zona (ShadowMap; versión 14981f8), ed52e38 espiral con aros fundidos (HwBinding.splitFused; la espiral de cuaderno4 reaparecía con la nitidez restaurada), 6a34683 sin sombras en páginas diminutas (TDD 200 px: el texto borroso se tomaba por sombra). APK: scratchpad/EscanerProMax-v11-arm64.apk. Verificación sin OCR (int3_v11_v2 = manifest_v2 --limit 25; int3_v11_v3, int3_v11_dref, int3_v11b_fotos) y hojas en /tmp/claude-0/bench/final_sheets_v3/. Notas: /tmp/claude-0/bench/notes_integracion_v3.md. Worktrees de la ronda v3 eliminados.
- **Siguiente ronda**: línea base con manifest_v3 (2275) y mejoras, empezando por los FALSOS DOCUMENTOS (87,5 % de negativos COCO v3 con cuadrilátero). Pendiente menor: bloques grises extra en fotos de medio tono de algunos escaneos (dival_p053, findImageBlocks con el fondo re-iluminado).
  Además: agente buscando más datasets (sombras, desenfoque, pantallas, libros, pizarras, español, negativos) → /tmp/claude-0/bench/manifest_v3.csv; notas en notes_datasets_v3.md. HECHO: manifest_v3.csv = 2275 imágenes (+662: sombras con referencia Jung/OSR/RDD/RealDAE, desenfoque Hradiš/SmartDoc-QA, muaré, DocReal, UDIR, español, pizarras, WildReceipt, BID, negativos COCO/Openverse, tinta de color). Hallazgo: falsos documentos en 87,5 % de negativos COCO v3. Siguiente ronda (tras integrar v3): línea base con manifest_v3 y mejoras (falsos documentos primero).
- Ronda v2 INTEGRADA en `ccr-4d2f0b52-3ktfkq` (detección, hojas dobladas, legibilidad, robustez + 3 correcciones de
  integración: EdgeClean respeta los cantos, renglón fino != raya, cuadrícula tramada en el PDF B/N).
  - Banco completo `integrado_v2` (1613 imágenes, con OCR): informe en `/tmp/claude-0/bench/REPORT_v2.md`,
    hojas en `/tmp/claude-0/bench/final_sheets_v2/`.
  - APK release compilado (app/build/outputs/apk/release/). Pendiente: enviarlo al usuario con el informe (v10).
  - Worktrees de los agentes eliminados.

## Peticiones del usuario pendientes
1. (HECHO en v2) Pintar de blanco las manchas de los bordes que no son texto.
2. (HECHO en v2) Conservar la cuadrícula/renglones del cuaderno, con interruptor "Conservar cuadrícula / renglones" (por defecto activado).
3. Enviar el APK nuevo + informe del banco al terminar la ronda (v11 lista para enviar).

## Banco de pruebas (fuera del repo, sobrevive a reinicios)
- `/tmp/claude-0/bench/` — README.md, REPORT.md, manifest.csv (333), manifest_v2.csv (1613).
- Comando: `run_bench.sh <repo> <out> --manifest manifest_v2.csv --shards 2 --chunk 120 --metric-procs 3 [--no-ocr] [--baseline base_v3] [--only ...]`.
- Referencia del HEAD e92c03d: `base_v3/`. Notas por área: `notes_<área>.md`.
- Fotos reales del usuario: scratchpad `fotos-usuario/` (cuaderno1–4, tabla1, captura_trazos_tenues).

## Historial de versiones entregadas
- v1–v2: app base, fluidez gama baja, algoritmos, PDF con texto.
- v3: arreglo del cierre al capturar, detección con fotos reales, 3 filtros.
- v4: recuadros de escritura + fondo blanco, resolución completa.
- v5: cámara (extensiones, enfoque, zoom, resolución visible, cámara del teléfono), impresos/tablas.
- v6: enderezado por malla de líneas (tablas, cuadrícula).
- v7: trazos manuscritos uniformes, sin pérdida en bordes.
- v8: banco de 333 imágenes y 4 mejoras (detección 44%→22,6% de fallo, DIBCO FM 43→73,6).
- v9: enderezado con renglones y márgenes del texto.
- v10 (2026-10-10, commit df776c4): ronda v2 con banco de 1613 imágenes — detección (fallo 45,6→40,8 %), hojas dobladas por trozos, legibilidad/tipo de página, bordes a blanco, cuadrícula conservada con interruptor, tinta de color en B/N, 0 errores de tubería.
- v11 (2026-10-12, commit 6a34683): ronda v3 — sombras de mano/móvil tratadas por zona (RDD MAGIC FM 76,7→79,4; sombra_ref 78,0→79,1), zonas alejadas borrosas y fantasma por movimiento restaurados (pérdida de trazo MAGIC 2,41→2,32 %), fusión de ráfaga sin fantasmas, espiral robusta a aros fundidos.
