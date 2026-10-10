# PROGRESO — ESCÁNER PRO MAX

Registro vivo del estado del proyecto para poder retomar tras cualquier fallo o reinicio.
Actualízalo en cada paso importante (y haz commit + push).

## Estado actual (2026-10-10)
- Rama de trabajo: `ccr-4d2f0b52-3ktfkq` (se sube siempre a `origin`).
- Último APK entregado al usuario: v9 (enderezado con guías de texto, commit e92c03d).
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
