// Side panel for the macOS demo. Read-only view of the live runtime:
//   * Registry: every renderer the host has wired up
//   * Envelope log: most-recent SSE frames (op summary, resolves
//     intent prefix, timestamp)
//   * Live state: the current TreeStore.state mirror
//
// The brief calls for "tree state inspector, patches-in-flight log,
// optimistic prediction diff, registry inspector". Phase 1.H ships
// the registry + envelope log + state. Optimistic prediction diff
// lands when iOS gets shared morphs in phase 4.

import SwiftUI
import Wun

struct DevtoolsPanel: View {
    @ObservedObject var vm: AppViewModel
    @ObservedObject var store: TreeStore

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Devtools")
                .font(.headline)
                .padding(.bottom, 4)

            section("Registry (\(vm.registry.registered().count))") {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(vm.registry.registered(), id: \.self) { tag in
                            Text(tag)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundColor(.primary)
                        }
                    }
                }
                .frame(maxHeight: 140)
            }

            section("Envelopes (\(vm.envelopeLog.count))") {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        // Newest first.
                        ForEach(vm.envelopeLog.reversed()) { entry in
                            envelopeRow(entry)
                        }
                    }
                }
                .frame(maxHeight: 220)
            }

            section("State") {
                Text(describe(store.state))
                    .font(.system(.caption, design: .monospaced))
                    .foregroundColor(.secondary)
                    .textSelection(.enabled)
            }

            section("Connection") {
                Text("conn-id: \(store.connID.map { String($0.prefix(8)) } ?? "—")")
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundColor(.secondary)
                Text("stack: \(store.screenStack.joined(separator: " > "))")
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundColor(.secondary)
            }

            Spacer()
        }
        .padding(12)
        .frame(width: 320)
        .background(Color.gray.opacity(0.06))
    }

    private func section<Content: View>(
        _ title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(.caption, design: .monospaced))
                .foregroundColor(.secondary)
                .textCase(.uppercase)
            content()
        }
    }

    private func envelopeRow(_ entry: EnvelopeLogEntry) -> some View {
        HStack(spacing: 6) {
            Text(Self.timeFormatter.string(from: entry.timestamp))
                .font(.system(size: 10, design: .monospaced))
                .foregroundColor(.secondary)
            Text(entry.summary)
                .font(.system(.caption2, design: .monospaced))
                .foregroundColor(.primary)
                .lineLimit(1)
            Spacer(minLength: 4)
            if let r = entry.resolves {
                Text(r)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundColor(.secondary)
            }
        }
    }

    private func describe(_ state: JSON) -> String {
        if case .object(let o) = state {
            return o.keys
                .sorted()
                .map { "\($0): \(short(o[$0]!))" }
                .joined(separator: ", ")
                .prefix(200)
                .description
        }
        return String(describing: state).prefix(200).description
    }

    private func short(_ json: JSON) -> String {
        switch json {
        case .null:           return "null"
        case .bool(let b):    return String(b)
        case .int(let i):     return String(i)
        case .double(let d):  return String(d)
        case .string(let s):  return "\"\(s)\""
        case .array:          return "[…]"
        case .object:         return "{…}"
        }
    }
}
