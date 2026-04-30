// SwiftUI renderer for `:wun/Stack`. Direction defaults to column;
// `:row` switches to HStack. `:gap` and `:padding` are pixel ints.
//
// User code registers the same way:
//   registry.register("myapp/Card", MyAppCard.render)

import SwiftUI

public enum WunStack {
    public static let render: WunComponent = { props, children in
        let direction = props["direction"]?.stringValue ?? "column"
        let gap       = (props["gap"]?.intValue).map { CGFloat($0) }     ?? 0
        let pad       = (props["padding"]?.intValue).map { CGFloat($0) } ?? 0

        return AnyView(
            Group {
                if direction == "row" {
                    HStack(alignment: .center, spacing: gap) {
                        ForEach(Array(children.enumerated()), id: \.offset) { _, kid in
                            WunView(kid)
                        }
                    }
                } else {
                    VStack(alignment: .leading, spacing: gap) {
                        ForEach(Array(children.enumerated()), id: \.offset) { _, kid in
                            WunView(kid)
                        }
                    }
                }
            }
            .padding(pad)
        )
    }
}
