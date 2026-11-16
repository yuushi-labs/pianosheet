# ONNX Runtime reaches its native bridge through JNI.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Room entities are instantiated reflectively by the generated code.
-keep class com.yuushilabs.pianosheet.data.db.** { *; }
