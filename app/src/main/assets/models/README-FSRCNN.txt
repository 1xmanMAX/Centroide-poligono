Modelos de super-resolución x2 (FSRCNN, Dong et al. 2016)
=========================================================

fsrcnn_x2.onnx        (d=56, s=12, m=4)  ~35 KB  -> gama media/alta
fsrcnn_small_x2.onnx  (d=32, s=5,  m=1)  ~7 KB   -> gama baja

Origen de los pesos: repositorio público Saafke/FSRCNN_Tensorflow
(https://github.com/Saafke/FSRCNN_Tensorflow, archivos models/FSRCNN_x2.pb y models/FSRCNN-small_x2.pb),
publicado bajo licencia Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
Copyright de los pesos originales: Xavier Weber (Saafke) y colaboradores.

Modificaciones (ESCÁNER PRO MAX): los pesos se extrajeron de los .pb de TensorFlow y se re-empaquetaron en
ONNX (opset 11). La última capa "conv 1x1 (4 canales) + DepthToSpace + bias", que el importador de TensorFlow
de OpenCV 4.10 no soporta (capa DepthToSpace), se expresó de forma matemáticamente equivalente como
ConvTranspose 2x2 con paso 2 + bias. Entrada: canal Y en [0,1] (1x1xHxW); salida: Y x2 (1x1x2Hx2W).
Verificado con OpenCV 4.10 (cv2.dnn.readNetFromONNX): +1.1..1.8 dB PSNR frente a bicúbico en texto.
