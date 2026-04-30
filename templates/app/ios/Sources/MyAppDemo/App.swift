// SwiftUI macOS / iOS demo. Mirrors wun-ios-example/Sources/WunDemoMac
// but stripped to a minimum: register the foundational :wun/*
// renderers, open an SSE connection, hand the live tree to WunView.

import SwiftUI
import Wun

@main
struct MyAppDemo: App {
    @StateObject private var vm = AppViewModel()

    var body: some Scene {
        WindowGroup("myapp") {
            ContentView(vm: vm, store: vm.store)
                .environment(\.locale, .init(identifier: "en"))
        }
    }
}

struct ContentView: View {
    @ObservedObject var vm: AppViewModel
    @ObservedObject var store: TreeStore

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Circle()
                    .fill(vm.status == "connected" ? .green : .orange)
                    .frame(width: 8, height: 8)
                Text(vm.status)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)

            Divider()

            ScrollView {
                CompositionLocalProvider {
                    WunView(store.tree)
                }
                .padding(20)
            }
        }
        .frame(minWidth: 480, minHeight: 540)
    }
}

private struct CompositionLocalProvider<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        content()
            .environment(\.wunRegistry, AppViewModel.shared.registry)
    }
}
