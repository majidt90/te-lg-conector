# MediaBridge - release build notes
#
# The release build has no reflective/serialization frameworks, so the rules
# only need to keep what the platform instantiates by name (components) and
# what the webOS/DLNA JSON layer reflects on (none - the tiny JSON reader is
# hand written). Keep the manifest-referenced classes explicitly for safety.

-keep class com.lgmediabridge.ui.**Activity { *; }
-keep class com.lgmediabridge.service.** { *; }
-keep class com.lgmediabridge.App { *; }

# Preserve useful stack traces while still shrinking
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
