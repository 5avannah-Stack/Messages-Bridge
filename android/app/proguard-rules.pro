# Crash reports keep their line numbers; the file names themselves don't matter.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room (pulled in by WorkManager) creates its database through the generated class's no-argument constructor, found by
# reflection, which Room's own rule doesn't keep.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
