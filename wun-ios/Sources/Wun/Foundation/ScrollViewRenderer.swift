// SwiftUI renderer for `:wun/ScrollView`. `:direction` picks the
// axis (default vertical); children are wrapped in the appropriate
// stack inside the ScrollView so they lay out the way a Wun author
// would expect.

import SwiftUI

public enum WunScrollView {
    public static let render: WunComponent = { props, children in
        let horizontal = props["direction"]?.stringValue == "horizontal"
        let axis: Axis.Set = horizontal ? .horizontal : .vertical
        return AnyView(
            ScrollView(axis) {
                if horizontal {
                    HStack(spacing: 8) {
                        ForEach(Array(children.enumerated()), id: \.offset) { _, kid in
                            WunView(kid)
                        }
                    }
                } else {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(Array(children.enumerated()), id: \.offset) { _, kid in
                            WunView(kid)
                        }
                    }
                }
            }
        )
    }
}
