# AppWidgetProvider and the services are referenced only from the manifest.
-keep class com.n3d.spectra.widget.SpectraWidgetProvider { *; }
-keep class com.n3d.spectra.service.** { *; }

# ONNX Runtime's native half constructs and calls into these classes by name
# over JNI; R8 cannot see those references and would strip or rename them.
-keep class ai.onnxruntime.** { *; }
