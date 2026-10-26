# JNA resolves native methods, callback methods and Structure fields by name.
# Keep the small FFI boundary; ordinary Kotlin/Compose code remains shrinkable.
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keep class com.flux.manga.core.** extends com.sun.jna.Structure { *; }
-keep interface com.flux.manga.core.** extends com.sun.jna.Library { *; }
-keep interface com.flux.manga.core.** extends com.sun.jna.Callback { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature

# JNA also exposes desktop window-handle helpers; Android never calls Native.AWT.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
