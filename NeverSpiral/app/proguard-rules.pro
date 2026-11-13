# Release builds are shrunk and optimised by R8. The app's own code is small and uses no
# reflection, but it is kept whole so a missing keep rule can never remove something the
# camera pipeline needs at runtime. Libraries (Compose, ML Kit, Media3) ship their own rules.
-keep class com.nikoezveracamspiral.** { *; }
-ignorewarnings
-keep class org.tensorflow.lite.** { *; }
