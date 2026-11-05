# Library consumer rules (Media3, Compose, Coil) cover what is needed.
# Keep readable crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Cast framework instantiates this by name from the manifest.
-keep class com.mashwar.tv.CastOptionsProvider { *; }
