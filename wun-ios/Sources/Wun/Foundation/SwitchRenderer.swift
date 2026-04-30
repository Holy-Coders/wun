// SwiftUI renderer for `:wun/Switch`. `:value` controls the toggle
// position; `:on-toggle` fires the intent with the new boolean value
// merged into params under `value`.

import SwiftUI

public enum WunSwitch {
    public static let render: WunComponent = { props, children in
        let value    = props["value"]?.boolValue ?? false
        let onToggle = props["on-toggle"]?.objectValue
        let label    = WunChildren.flatten(children)
        return AnyView(SwitchView(value: value, label: label, onToggle: onToggle))
    }

    private struct SwitchView: View {
        let value: Bool
        let label: String
        let onToggle: [String: JSON]?
        @State private var local: Bool

        init(value: Bool, label: String, onToggle: [String: JSON]?) {
            self.value = value
            self.label = label
            self.onToggle = onToggle
            self._local = State(initialValue: value)
        }

        var body: some View {
            HStack(spacing: 8) {
                Toggle("", isOn: Binding(
                    get: { local },
                    set: { newValue in
                        local = newValue
                        if let press = onToggle,
                           case .string(let intent) = press["intent"] ?? .null {
                            var params = press["params"]?.objectValue ?? [:]
                            params["value"] = .bool(newValue)
                            Task { @MainActor in
                                Wun.intentDispatcher(intent, params)
                            }
                        }
                    }))
                    .labelsHidden()
                if !label.isEmpty {
                    SwiftUI.Text(label)
                }
            }
            .onChange(of: value) { newValue in
                // Server confirmation snaps the optimistic local back
                // in line if it diverged.
                if local != newValue { local = newValue }
            }
        }
    }
}
