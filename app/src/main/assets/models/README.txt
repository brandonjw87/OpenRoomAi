efficientdet_lite0.tflite
  EfficientDet-Lite0 object detector (COCO, 320x320 uint8 input, 25 detections with
  built-in post-processing), as published for the TensorFlow Lite Task Library examples:
  https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite
  SHA-256 2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b
  License: Apache License 2.0 (TensorFlow model garden / TF Hub).

coco_labels.txt
  The label map embedded in the model's metadata (91 entries, "???" = unused COCO ids).

Outputs (verified with the LiteRT interpreter): 0 = boxes [1,25,4] (ymin, xmin, ymax, xmax,
normalized), 1 = classes [1,25], 2 = scores [1,25], 3 = count [1].
