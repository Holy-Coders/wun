// The main window's root view. Renders the live Wun tree via WunView,
// surfaces the SSE connection status, and mirrors the screen state at
// the bottom for quick observation during dev.
//
// We observe both `vm` (for status) AND `store` (for tree + state
// updates) because TreeStore's @Published notifications go through
// its own objectWillChange, not through AppViewModel's.

import SwiftUI
import Wun

struct ContentView: View {
    @EnvironmentObject var vm: AppViewModel
    @ObservedObject var store: TreeStore

    var body: some View {
        VStack(spacing: 0) {
            statusBar
            Divider()

            ScrollView {
                WunView(store.tree)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(20)
            }

            Divider()
            stateBar
        }
        // Plumb the registry to every descendant WunView so renderers
        // like WunStack / WunCard / WunList that recursively call
        // WunView(kid) without an explicit registry pick up the same
        // one the host wired up.
        .environment(\.wunRegistry, vm.registry)
        .frame(minWidth: 480, minHeight: 540)
    }

    private var statusBar: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(vm.status == "connected" ? Color.green : Color.orange)
                .frame(width: 8, height: 8)
            Text(vm.status)
                .font(.system(.caption, design: .monospaced))
                .foregroundColor(.secondary)
            Spacer()
            Text("Wun \(Wun.version)")
                .font(.system(.caption2, design: .monospaced))
                .foregroundColor(.secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    private var stateBar: some View {
        HStack {
            Text("state: \(describe(store.state))")
                .font(.system(.caption2, design: .monospaced))
                .foregroundColor(.secondary)
            Spacer()
            if let id = store.lastResolvedIntent {
                Text("last resolved: \(id.prefix(8))")
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundColor(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    private func describe(_ state: JSON) -> String {
        if case .object(let o) = state, let counter = o["counter"]?.intValue {
            return "{counter: \(counter)}"
        }
        return "\(state)"
    }
}
