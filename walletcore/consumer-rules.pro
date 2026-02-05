# consumer-rules.pro
#
# This file is packaged into the AAR and applied automatically when an application
# consumes the `:walletcore` library.
#
# Goal:
# - Keep rules minimal.
# - Avoid over-keeping classes unnecessarily.
#
# Current state:
# - The walletcore module exposes a small Kotlin API surface and uses JNI.
# - JNI method names are bound by convention (Java_com_... symbols) in C++,
#   so ProGuard/R8 shrinking typically does not break native bindings.
#
# Add rules here only if/when you introduce:
# - Reflection on Kotlin/Java classes,
# - Serialized/deserialized DTOs that require preserved field names,
# - Or if you switch JNI to RegisterNatives + reflective lookups.

# Keep the public Kotlin entry point stable (conservative; safe for future refactors).
-keep class com.nexatrode.nexawal.walletcore.WalletCore { *; }

# If you later add public exceptions or result DTOs that cross module boundaries,
# consider keeping them as well (uncomment as needed).
# -keep class com.nexatrode.nexawal.walletcore.** { *; }

# Preserve Kotlin metadata (useful for debugging/stack traces; generally low risk).
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }
