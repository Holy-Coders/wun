// SwiftUI renderer for `:wun/Button`. `:on-press` is an intent ref
// `{:intent ... :params {...}}` -- when the user taps the button the
// intent is fired through `Wun.intentDispatcher`, which the host app
// wires up to a real POST in phase 2.E.

import SwiftUI

public enum WunButton {
    public static let render: WunComponent = { props, children in
        let title   = WunChildren.flatten(children)
        let onPress = props["on-press"]?.objectValue ?? [:]
        return AnyView(
            SwiftUI.Button(action: {
                guard case .string(let intent) = onPress["intent"] ?? .null else { return }
                let params = onPress["params"]?.objectValue ?? [:]
                Task { @MainActor in
                    Wun.intentDispatcher(intent, params)
                }
            }) {
                SwiftUI.Text(title)
            }
            .buttonStyle(.bordered)
        )
    }
}
