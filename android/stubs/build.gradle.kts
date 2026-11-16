// Compile-only stubs for @SystemApi classes that exist in the device framework.
// Never packaged into the APK; at runtime the framework's real classes are used.
plugins { `java-library` }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
dependencies { compileOnly(files("${System.getenv("ANDROID_HOME") ?: (System.getProperty("user.home") + "/Android/Sdk")}/platforms/android-37.0/android.jar")) }
