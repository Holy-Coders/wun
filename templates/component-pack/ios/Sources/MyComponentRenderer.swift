// Template SwiftUI renderer. The closure signature is the same for
// every component:
//
//     (props: [String: JSON], children: [WunNode]) -> AnyView
//
// `WunChildren.flatten(children)` collapses any string children into
// a single label; otherwise iterate the children yourself with
// `WunView(kid)` so they render through the registry.

import SwiftUI
import Wun

public enum MyAppMyComponent {
    public static let render: WunComponent = { props, children in
        let label = props["label"]?.stringValue
            ?? WunChildren.flatten(children)
        return AnyView(
            HStack(spacing: 6) {
                SwiftUI.Text("✨")
                SwiftUI.Text(label.isEmpty ? "Hello from MyComponent" : label)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(Color.accentColor.opacity(0.08))
            .cornerRadius(8)
        )
    }
}
