// The main window's root view. Renders the live Wun tree via WunView,
// surfaces the SSE connection status, and mirrors the screen state at
// the bottom for quick observation during dev.

import SwiftUI
import Wun

struct ContentView: View {
    @EnvironmentObject var vm: AppViewModel

    var body: some View {
        VStack(spacing: 0) {
            statusBar
            Divider()

            ScrollView {
                WunView(vm.store.tree, registry: vm.registry)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(20)
            }

            Divider()
            stateBar
        }
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
            Text("state: \(describe(vm.store.state))")
                .font(.system(.caption2, design: .monospaced))
                .foregroundColor(.secondary)
            Spacer()
            if let id = vm.store.lastResolvedIntent {
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
