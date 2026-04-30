// SwiftUI renderer for `:wun/Input`. The component is a controlled
// input on the wire (`:value` is the server's authoritative copy)
// but takes local edits through SwiftUI's `@State` until the user
// commits, at which point we fire `:on-change` with the new value.
// Phase 2.E POSTs the intent for real; phase 1.C-style optimistic
// prediction lands on iOS in phase 4 alongside SCI.

import SwiftUI

public enum WunInput {
    public static let render: WunComponent = { props, _ in
        let value       = props["value"]?.stringValue ?? ""
        let placeholder = props["placeholder"]?.stringValue ?? ""
        let onChange    = props["on-change"]?.objectValue
        return AnyView(
            WunInputView(
                serverValue: value,
                placeholder: placeholder,
                onChange:    onChange
            )
        )
    }
}

private struct WunInputView: View {
    @State private var local: String

    let serverValue: String
    let placeholder: String
    let onChange:    [String: JSON]?

    init(serverValue: String, placeholder: String, onChange: [String: JSON]?) {
        self.serverValue = serverValue
        self.placeholder = placeholder
        self.onChange    = onChange
        self._local      = State(initialValue: serverValue)
    }

    var body: some View {
        // Note: server-pushed updates to `serverValue` won't sync into
        // `local` while the user is mid-edit. Bridging that requires
        // an .onChange(of:) hop that's awkward across the macOS 12 / 14
        // SwiftUI API split. Phase 4 (optimistic UI on native) will
        // tighten this up; for now, the user's local edits commit on
        // submit and the server's authoritative value is what re-renders
        // when the InputRenderer is freshly invoked.
        TextField(placeholder, text: $local)
            .textFieldStyle(.roundedBorder)
            .onSubmit(commit)
    }

    @MainActor
    private func commit() {
        guard let onChange,
              case .string(let intent) = onChange["intent"] ?? .null
        else { return }
        var params = onChange["params"]?.objectValue ?? [:]
        params["value"] = .string(local)
        Wun.intentDispatcher(intent, params)
    }
}
