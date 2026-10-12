# PROGRESO — ESCÁNER PRO MAX

Registro vivo del estado del proyecto para poder retomar tras cualquier fallo o reinicio.
Actualízalo en cada paso importante (y haz commit + push).

## Estado actual (2026-10-10)
- Rama de trabajo: `ccr-4d2f0b52-3ktfkq` (se sube siempre a `origin`).
- Último APK entregado al usuario: v10 (ronda v2 integrada, commit df776c4). En curso (2026-10-12): integrar v3 con un solo agente (sombras WIP en wf_95a07d21-bba-1, fantasma/desenfoque WIP b2381df en wf_95a07d21-bba-2) y entregar v11. Antes (2026-10-11): ronda v3 — sombras: worktree wf_95a07d21-bba-1 WIP 38a32c9, banco sombras_final hecho; desenfoque: worktree wf_95a07d21-bba-2 final e26fb51 (fantasma por movimiento aún visible → se añade quitar 'imagen doble'); integración pendiente. Ronda v3 original: — (1) sombras tratadas por zona, (2) zonas alejadas borrosas/desenfoque por movimiento. Fotos de prueba: fotos-usuario/colores_sombra1..3.jpg (tintas de colores + sombra de mano). Workflow "escaner-sombras-desenfoque"; notas en /tmp/claude-0/bench/notes_sombras.md y notes_desenfoque.md.
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
3. Enviar el APK nuevo + informe del banco al terminar la ronda.

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
