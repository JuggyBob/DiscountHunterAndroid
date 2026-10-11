# Discount Hunter - R8 / ProGuard rules.
#
# The app uses no reflection-based serialization (the store payloads are read through the
# kotlinx.serialization JSON *tree* API), so the default AGP/Compose/Media3 rules are enough.
# Only classes reached through the manifest or the platform need to survive explicitly.

# Keep line numbers so release crash reports stay readable, but hide the original file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
