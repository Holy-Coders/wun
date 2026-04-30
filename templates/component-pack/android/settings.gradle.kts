rootProject.name = "myapp-example"

// Composite build pointing at the wun-android library so we can
// `implementation("wun:wun")` without publishing.
includeBuild("../../../wun-android")
