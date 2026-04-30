// SwiftUI renderer for `:wun/Divider`. Maps to a 1-pt grey rectangle
// stretched horizontally; `:thickness` overrides the height.

import SwiftUI

public enum WunDivider {
    public static let render: WunComponent = { props, _ in
        let thickness = (props["thickness"]?.intValue).map { CGFloat($0) } ?? 1
        return AnyView(
            Rectangle()
                .fill(Color.gray.opacity(0.25))
                .frame(height: thickness)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)
        )
    }
}
