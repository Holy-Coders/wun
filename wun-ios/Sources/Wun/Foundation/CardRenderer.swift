// SwiftUI renderer for `:wun/Card`. Optional `:title` headline, then
// children stacked vertically, all inside a padded rounded surface.

import SwiftUI

public enum WunCard {
    public static let render: WunComponent = { props, children in
        let title = props["title"]?.stringValue
        return AnyView(
            VStack(alignment: .leading, spacing: 8) {
                if let t = title {
                    SwiftUI.Text(t).font(.system(size: 18, weight: .semibold))
                }
                ForEach(Array(children.enumerated()), id: \.offset) { _, kid in
                    WunView(kid)
                }
            }
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.gray.opacity(0.08))
            )
        )
    }
}
