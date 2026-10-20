# Discount Hunter - R8 / ProGuard rules.
#
# The app uses no reflection-based serialization (the store payloads are read through the
# kotlinx.serialization JSON *tree* API), so the default AGP/Compose/Media3 rules are enough.
# Only classes reached through the manifest or the platform need to survive explicitly.

# Keep line numbers so release crash reports stay readable, but hide the original file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- WorkManager -------------------------------------------------------------
#
# WorkManager keeps its schedule in a Room database, and Room instantiates the generated
# implementation reflectively: `WorkDatabase_Impl` is created by name, with a no-argument
# constructor, by a call R8 cannot see.
#
# Without this, R8 removes the class and the *release* build dies on launch, before any screen
# is drawn:
#
#   java.lang.RuntimeException: Unable to get provider androidx.startup.InitializationProvider
#   Caused by: java.lang.NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
#
# The debug build is unaffected, because R8 only runs for release - which is exactly how a crash
# like this reaches users and never reaches the person developing it.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.** { *; }
-dontwarn androidx.work.**
