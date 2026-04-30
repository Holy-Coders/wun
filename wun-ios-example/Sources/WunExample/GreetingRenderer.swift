// SwiftUI renderer for `:myapp/Greeting`. Just a small example of a
// user-defined component: takes a `:name` prop and renders a styled
// hello banner. Same shape as the foundational `:wun/*` renderers,
// because the registry doesn't distinguish framework from user code.

import SwiftUI
import Wun

public enum Greeting {
    public static let render: WunComponent = { props, _ in
        let name = props["name"]?.stringValue ?? "world"
        return AnyView(
            HStack(spacing: 8) {
                Image(systemName: "hand.wave.fill")
                    .foregroundColor(.orange)
                SwiftUI.Text("Hello, \(name)!")
                    .font(.system(size: 18, weight: .semibold))
            }
            .padding(.vertical, 4)
        )
    }
}
