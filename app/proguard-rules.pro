# Mink R8 / ProGuard rules for the minified release build.
#
# The app leans on library-supplied consumer rules for almost everything
# (kotlinx.serialization ships $$serializer keep rules, AndroidX/Compose/
# WorkManager/DataStore ship their own, and manifest-declared components are
# kept by AGP automatically). Only the one surface R8 cannot infer — the JNI
# seam — needs an explicit rule here.

# ── On-device LLM JNI seam ──────────────────────────────────────────────────
# LlamaBridge's `external fun`s are bound at runtime by the native mink_llm
# library through their exact `Java_com_mink_guardian_llm_LlamaBridge_<name>`
# symbol names, which depend on both the class's fully-qualified name and each
# method's name staying intact. R8's default configuration already keeps
# `native <methods>` for every class, but that is an implicit backstop — make
# the contract explicit so a change to the default rules can never silently
# rename or strip the seam and break the model at runtime (UnsatisfiedLinkError
# with no compile-time signal). `includedescriptorclasses` keeps the types in
# the method signatures too.
-keep,includedescriptorclasses class com.mink.guardian.llm.LlamaBridge {
    native <methods>;
}
