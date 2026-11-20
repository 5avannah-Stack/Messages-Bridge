# Crash reports keep their line numbers; the file names themselves don't matter.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room (pulled in by WorkManager) creates its database through the generated class's no-argument constructor, found by
# reflection, which Room's own rule doesn't keep.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# gomobile's native code finds its Java side by name through JNI: the go.Seq runtime and the bound package's classes,
# including the Listener proxy that carries events back. R8 must neither drop nor rename them.
-keep class go.** { *; }
-keep class cz.teous.messages.bridge.go.** { *; }
