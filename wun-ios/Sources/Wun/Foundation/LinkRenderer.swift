// SwiftUI renderer for `:wun/Link`. When `:on-press` is set the tap
// fires that intent (parallel of `:wun/Button`); otherwise the link
// opens `:href` in the default browser via `UIApplication`/`NSWorkspace`
// abstracted behind `openURL`.

import SwiftUI

public enum WunLink {
    public static let render: WunComponent = { props, children in
        let title   = WunChildren.flatten(children)
        let onPress = props["on-press"]?.objectValue
        let href    = props["href"]?.stringValue ?? "#"
        return AnyView(LinkLabel(title: title, onPress: onPress, href: href))
    }

    private struct LinkLabel: View {
        let title: String
        let onPress: [String: JSON]?
        let href: String
        @Environment(\.openURL) private var openURL

        var body: some View {
            SwiftUI.Button(action: {
                if let press = onPress,
                   case .string(let intent) = press["intent"] ?? .null {
                    let params = press["params"]?.objectValue ?? [:]
                    Task { @MainActor in
                        Wun.intentDispatcher(intent, params)
                    }
                } else if let url = URL(string: href) {
                    openURL(url)
                }
            }) {
                SwiftUI.Text(title)
                    .foregroundColor(.accentColor)
                    .underline()
            }
            .buttonStyle(.plain)
        }
    }
}
