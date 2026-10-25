Encuadre IA: DocAligner (DocsaidLab) - modelo de esquinas por mapas de calor
===========================================================================

docaligner_lcnet100.onnx  (4 767 987 bytes)
  sha256 f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e
  = lcnet100_h_e_bifpn_256_fp32.onnx de DocAligner 1.1.1 (sin modificar; solo renombrado)

Origen: https://github.com/DocsaidLab/DocAligner (Ze Yuan / DocsaidLab, 2023)
Licencia: Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
Copyright (c) 2023 DocsaidLab / Ze Yuan y colaboradores.

Uso en ESCÁNER PRO MAX (imaging/NeuralCorners.kt, reimplementación propia del pre/postproceso):
entrada 1x3x256x256 BGR/255 (foto con marco negro del 15 % por lado, estirada sin conservar proporción);
salida 1x4x128x128 mapas de calor (sigmoide) de las esquinas TL, TR, BR, BL. Se usa como ÁRBITRO del detector
clásico (DocumentDetector.neuralArbitrate), no como detector único.
