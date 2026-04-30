rootProject.name = "wun-android-example"

// Composite build: depend on wun-android by source path so changes
// there are picked up immediately, no maven-publish dance needed.
includeBuild("../wun-android")
