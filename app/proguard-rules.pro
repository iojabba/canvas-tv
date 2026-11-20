# Layout JSON uses kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.canvastv.dashboard.model.** { *; }
-keep,includedescriptorclasses class com.canvastv.dashboard.model.**$$serializer { *; }
-keepclassmembers class com.canvastv.dashboard.model.** { *** Companion; }
