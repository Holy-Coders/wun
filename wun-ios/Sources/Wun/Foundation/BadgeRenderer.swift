// SwiftUI renderer for `:wun/Badge`. `:tone` picks a semantic colour
// (info / success / warning / danger). Falls back to `info` for any
// unknown tone string so the server can introduce new tones without
// hard-breaking older clients.

import SwiftUI

public enum WunBadge {
    public static let render: WunComponent = { props, children in
        let tone = props["tone"]?.stringValue ?? "info"
        let label = WunChildren.flatten(children)
        let (bg, fg): (Color, Color) = {
            switch tone {
            case "success": return (Color(red: 0.88, green: 0.96, blue: 0.91),
                                    Color(red: 0.10, green: 0.42, blue: 0.23))
            case "warning": return (Color(red: 1.00, green: 0.95, blue: 0.84),
                                    Color(red: 0.48, green: 0.32, blue: 0.00))
            case "danger":  return (Color(red: 0.99, green: 0.89, blue: 0.89),
                                    Color(red: 0.61, green: 0.11, blue: 0.11))
            default:        return (Color(red: 0.91, green: 0.94, blue: 1.00),
                                    Color(red: 0.04, green: 0.31, blue: 0.64))
            }
        }()
        return AnyView(
            SwiftUI.Text(label)
                .font(.system(size: 11, weight: .semibold))
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(bg)
                .foregroundColor(fg)
                .cornerRadius(999)
        )
    }
}
