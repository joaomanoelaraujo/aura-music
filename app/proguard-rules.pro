# AURA release rules.
# Hilt, Room, Media3, Coil, Compose and kotlinx.serialization ship their own consumer rules.
# Navigation's type-safe routes are @Serializable classes; keep their generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.aura.music.navigation.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Navigation resolves enum route arguments by their fully-qualified class name at runtime.
-keepnames enum com.aura.music.navigation.** { *; }
