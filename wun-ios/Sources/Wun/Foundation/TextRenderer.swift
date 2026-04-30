// SwiftUI renderer for `:wun/Text`. `:variant` chooses a typography
// preset (h1 / h2 / body); children that are strings are joined to
// form the rendered text.

import SwiftUI

public enum WunText {
    public static let render: WunComponent = { props, children in
        let variant = props["variant"]?.stringValue ?? "body"
        let text    = WunText.flatten(children)

        switch variant {
        case "h1":
            return AnyView(SwiftUI.Text(text)
                .font(.system(size: 32, weight: .semibold)))
        case "h2":
            return AnyView(SwiftUI.Text(text)
                .font(.system(size: 22, weight: .semibold)))
        default:
            return AnyView(SwiftUI.Text(text)
                .font(.system(size: 15))
                .opacity(0.85))
        }
    }

    private static func flatten(_ children: [WunNode]) -> String {
        children.compactMap { node -> String? in
            switch node {
            case .text(let s):   return s
            case .number(let n): return formatNumber(n)
            case .bool(let b):   return String(b)
            default:             return nil
            }
        }.joined()
    }

    private static func formatNumber(_ n: Double) -> String {
        if n.truncatingRemainder(dividingBy: 1) == 0,
           abs(n) < Double(Int64.max) {
            return String(Int64(n))
        }
        return String(n)
    }
}
