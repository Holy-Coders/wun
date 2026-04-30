// SwiftUI renderer for `:wun/List`. Vertical lazily-rendered stack
// of children. We use LazyVStack rather than SwiftUI.List because
// List forces its own row chrome that fights with arbitrary Wun
// components.

import SwiftUI

public enum WunList {
    public static let render: WunComponent = { _, children in
        return AnyView(
            LazyVStack(alignment: .leading, spacing: 8) {
                ForEach(Array(children.enumerated()), id: \.offset) { _, kid in
                    WunView(kid)
                }
            }
        )
    }
}
