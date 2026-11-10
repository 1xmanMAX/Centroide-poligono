Orientación de página: PP-LCNet_x1_0_doc_ori (PaddlePaddle / PaddleOCR) - clasificador 0/90/180/270
=====================================================================================================

pp_lcnet_doc_ori.onnx  (6 788 069 bytes)
  sha256 af9a0a4f317ff0709ce752067807f819cb15d883f8ecad89f28df1c6ee2d9c92
  = inference.onnx de https://huggingface.co/PaddlePaddle/PP-LCNet_x1_0_doc_ori_onnx (sin modificar; solo renombrado)
  (el mismo archivo que trae la descarga «Maquetación de páginas», ModelCatalog.ORIENTATION_FILE)

Origen: PaddleOCR (https://github.com/PaddlePaddle/PaddleOCR), PaddlePaddle Authors.
Licencia: Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0)

Uso en ESCÁNER PRO MAX (ai/parts/DocOrientation.kt + LayoutEngine.orientationProbs, OpenCV DNN): voto de 5 recortes
(página + 4 cuartos), entrada 1x3x224x224 normalizada ImageNet; es una de las señales del voto de orientación
automática (imaging/OrientationVote.kt) junto con los renglones y, si está activo, Jev.
