// SwiftUI entry point. Open this package in Xcode, pick the
// `wun-demo-mac` scheme, and run -- a window appears showing the
// counter screen the wun-server emits, with `:myapp/Greeting`
// rendered via the WunExample package's user-namespace renderer.
//
// From a terminal: `swift run wun-demo-mac` does the same thing.

import SwiftUI

@main
struct WunDemoApp: SwiftUI.App {
    @StateObject private var vm = AppViewModel()

    var body: some Scene {
        WindowGroup("Wun · phase 2 demo") {
            ContentView()
                .environmentObject(vm)
        }
    }
}
