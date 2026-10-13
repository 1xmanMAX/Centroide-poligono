# PROGRESO — ESCÁNER PRO MAX

Registro vivo del estado del proyecto para poder retomar tras cualquier fallo o reinicio.
Actualízalo en cada paso importante (y haz commit + push).

## Estado actual (2026-10-10)
- Rama de trabajo: `ccr-4d2f0b52-3ktfkq` (se sube siempre a `origin`).
- Último APK: **v12 LISTA (2026-10-12)** — halo + cuadrícula regular (abbc951, dd115a7, 491da42): borde de tinta limpio en Texto resaltado/B/N (PrintedPage: bilateral + croma antes del realce, realce acotado, cola del trazo a blanco; TextRegions: borde con el color del centro, alfa suavizado/re-afilado, tono sin cambio de matiz); cuadrícula reconstruida como RED REGULAR (RulingGrid.lattice: paso+fase por franja, zonas por fase, modelo suave, renumeración; sin medias rectas inventadas ni duplicados; completa) y restos de la rejilla original borrados a lo largo de la red; vista previa con las decisiones del render (PageProcessor: no cachea el modelo, reducción fija, opciones completas sin SR, try/finally ante cancelación); sombra profunda con escritura nunca se toma por fondo ajeno. APK: scratchpad/EscanerProMax-v12-arm64.apk. Banco hc_v12b (manifest_v2, 9 categorías --limit 25, sin OCR) vs int3_v11_v2: sin regresiones relevantes (B/N FM DIBCO +0.19, MAGIC FM DIBCO -0.4, pérdida MAGIC +0.03 pp, fondo más uniforme). Hojas: /tmp/claude-0/bench/halo_cuadricula_fotos/. Notas: /tmp/claude-0/bench/notes_halo_cuadricula.md.
- APK anterior: **v11 (2026-10-12)** — ronda v3 integrada en `ccr-4d2f0b52-3ktfkq`: a176448 desenfoque + fantasma (Deblur, Deghost, MultiFrameFusion.refineLocal; versión b2381df), 3c297fa sombras por zona (ShadowMap; versión 14981f8), ed52e38 espiral con aros fundidos (HwBinding.splitFused; la espiral de cuaderno4 reaparecía con la nitidez restaurada), 6a34683 sin sombras en páginas diminutas (TDD 200 px: el texto borroso se tomaba por sombra). APK: scratchpad/EscanerProMax-v11-arm64.apk. Verificación sin OCR (int3_v11_v2 = manifest_v2 --limit 25; int3_v11_v3, int3_v11_dref, int3_v11b_fotos) y hojas en /tmp/claude-0/bench/final_sheets_v3/. Notas: /tmp/claude-0/bench/notes_integracion_v3.md. Worktrees de la ronda v3 eliminados.
- **Siguiente ronda**: línea base con manifest_v3 (2275) y mejoras, empezando por los FALSOS DOCUMENTOS (87,5 % de negativos COCO v3 con cuadrilátero). Pendiente menor: bloques grises extra en fotos de medio tono de algunos escaneos (dival_p053, findImageBlocks con el fondo re-iluminado).
  Además: agente buscando más datasets (sombras, desenfoque, pantallas, libros, pizarras, español, negativos) → /tmp/claude-0/bench/manifest_v3.csv; notas en notes_datasets_v3.md. HECHO: manifest_v3.csv = 2275 imágenes (+662: sombras con referencia Jung/OSR/RDD/RealDAE, desenfoque Hradiš/SmartDoc-QA, muaré, DocReal, UDIR, español, pizarras, WildReceipt, BID, negativos COCO/Openverse, tinta de color). Hallazgo: falsos documentos en 87,5 % de negativos COCO v3. Siguiente ronda (tras integrar v3): línea base con manifest_v3 y mejoras (falsos documentos primero).
- Ronda v2 INTEGRADA en `ccr-4d2f0b52-3ktfkq` (detección, hojas dobladas, legibilidad, robustez + 3 correcciones de
  integración: EdgeClean respeta los cantos, renglón fino != raya, cuadrícula tramada en el PDF B/N).
  - Banco completo `integrado_v2` (1613 imágenes, con OCR): informe en `/tmp/claude-0/bench/REPORT_v2.md`,
    hojas en `/tmp/claude-0/bench/final_sheets_v2/`.
  - APK release compilado (app/build/outputs/apk/release/). Pendiente: enviarlo al usuario con el informe (v10).
  - Worktrees de los agentes eliminados.

## Peticiones del usuario pendientes
0. (HECHO en v12) Halo/aura alrededor de las letras y cuadrícula como red regular (separación constante, sin rayas inventadas a media distancia, sin restos oscuros de la original, líneas completas).
0b. (2026-10-12, PARCIAL en v12) Sombras que a veces NO se procesan aparte: mancha azul en la zona de sombra o, peor, zona de sombra BORRADA (hueco blanco con borde negro que se come el texto); franjas de madera/manchas naranjas en bordes. Capturas: fotos-usuario/v11_captura_sombra_vista1..3.jpg. (Añadido al agente de halo/cuadrícula.) v12: guarda en PrintedPage.outsideMask (mancha profunda casi neutra con escritura = hoja en sombra, nunca fondo ajeno). El hueco blanco NO se reprodujo con colores_sombra1–3 ni variantes recortadas (tiers bajo/medio): falta la foto original de esa página. Franjas de madera en bordes SIN resolver (la madera clara queda casi blanca tras normalizar; la sombra de la mano es más oscura que el teclado, así que ni color ni luminancia bastan): pendiente.
0c. (2026-10-12, en curso) Vista previa ≠ vista al abrir ≠ exportado; al abrir una hoja se reprocesa (5–10 s). Debe mostrarse directamente la imagen procesada final (la misma que se exporta) y reprocesar solo al editar. Agente aparte (UI/datos). Notas: /tmp/claude-0/bench/notes_vista_export.md. HECHO en rama local worktree-agent-a8f900963740d6253 (commit b2abce8, sin integrar): tocar hoja abre el visor con el procesado guardado; miniatura/visor/PDF del mismo archivo; editor muestra el procesado y calcula el render final real en segundo plano; exportación sin recorte. Cambios de PageProcessor pedidos al agente de algoritmos: HECHOS en v12 (dd115a7). INTEGRADO en v12 (cherry-pick sobre be08e6a; assembleRelease+tests OK).
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
- v12 (2026-10-12, commit 491da42): halo eliminado (borde de tinta limpio en Texto resaltado y B/N), cuadrícula reconstruida como red regular sin rayas inventadas ni restos oscuros, vista previa = render.
- v11 (2026-10-12, commit 6a34683): ronda v3 — sombras de mano/móvil tratadas por zona (RDD MAGIC FM 76,7→79,4; sombra_ref 78,0→79,1), zonas alejadas borrosas y fantasma por movimiento restaurados (pérdida de trazo MAGIC 2,41→2,32 %), fusión de ráfaga sin fantasmas, espiral robusta a aros fundidos.


## v12 (2026-10-12)
Halo + red regular de cuadrícula + vista previa = render (agente algoritmos, abbc951..be08e6a) + vista = exportado sin reprocesar (cherry-pick de b2abce8). APK scratchpad/EscanerProMax-v12-arm64.apk. Pendiente: franjas de madera/manchas de color en bordes (3 reglas probadas y revertidas), hueco blanco en sombra no reproducido (falta foto original), mancha azul no reproducida; probar v12 en emulador.

## Petición nueva (2026-10-13): modelo de lenguaje pequeño en el dispositivo
Usuario pide un LLM open source muy pequeño y moderno para mejorar OCR, reescribir PDF, etc. Investigado y propuesto: Qwen3.5-0.8B (Apache 2.0, feb 2026, multimodal, OCRBench 79,1, 201 idiomas) vía llama.cpp GGUF Q4 como descarga opcional. Alternativas: LFM2.5-350M (solo texto, gama muy baja, licencia LFM con límites comerciales), Gemma 4 E2B (más capaz pero ~2,3B efectivos). PENDIENTE: confirmación del usuario antes de implementar.
- Seguimiento: variantes de la comunidad investigadas. Candidatos: unsloth/Qwen3.5-0.8B-GGUF Q4_K_M (533 MB + mmproj F16 205 MB) para tareas generales; PaddleOCR-VL-1.5 GGUF (0.9B, Apache 2.0, OmniDocBench v1.5 94,5 vs 61,0 de Qwen3.5-0.8B; en llama.cpp) si la prioridad es OCR. Descartados: loay OCR 0.8B (CC-BY-NC, solo inglés), Granite-Docling 258M (falla con imágenes degradadas), GLM-OCR (MIT pero sin GGUF confirmado). Pendiente: elección del usuario y medir velocidad en móvil.

## EN CURSO (2026-10-13): IA en el dispositivo como motor PRINCIPAL (petición del usuario)
Usuario pide: usar PaddleOCR-VL-1.5 y Qwen3.5-0.8B como motores principales (no secundarios) para: reconocimiento de texto, tablas, gráficos/fórmulas; encuadre y desdoblado guiado (rejilla etiquetada sobre la imagen → el modelo da puntos de esquinas/pliegues); reconstrucción de PDF idéntico (misma disposición, texto real); mejora del documento. El usuario elige en ajustes qué modelo descargar (uno, otro o ambos, ahora o luego) con explicación de para qué sirve cada uno. Acepta alucinaciones ocasionales (el OCR actual falla mucho).
Plan por fases (agentes):
- F1 (agente "motor"): llama.cpp (submódulo, NDK 27.3 + CMake 3.31.6 instalados en /opt/android-sdk) por JNI con mtmd (visión), gestor de descargas de modelos (reanudable, verificación), pantalla "Modelos IA". Notas: /tmp/claude-0/bench/notes_ia_motor.md
- F2 (agente "banco IA", en paralelo): compilar llama.cpp en escritorio, bajar modelos a /tmp/claude-0/bench/models, diseñar prompts y formatos (Spotting, OCR, tablas, gráficos, puntos de encuadre con rejilla, maquetación con bbox), medir CER vs Tesseract en SROIE/FUNSD/CORD y fotos del usuario, elegir cuantización y URLs. Notas: /tmp/claude-0/bench/notes_ia_banco.md
- F3: integrar en la tubería (OCR principal, tablas, encuadre/desdoblado guiado, PDF reconstruido, mejora consciente de la maquetación). F4: revisión + APK v13.
