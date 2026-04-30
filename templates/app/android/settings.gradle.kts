rootProject.name = "myapp"

// By default we consume wun-android via a sibling composite build.
// Switch to JitPack once the wun repo is public + tagged:
//
//   pluginManagement {
//       repositories { gradlePluginPortal(); google(); mavenCentral() }
//   }
//   dependencyResolutionManagement {
//       repositories {
//           mavenCentral(); google()
//           maven("https://jitpack.io")
//       }
//   }
//   // and in build.gradle.kts:
//   //   implementation("com.github.Holy-Coders.wun:wun-android:0.1.0")

includeBuild("../../wun/wun-android")
