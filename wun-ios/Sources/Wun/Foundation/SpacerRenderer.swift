// SwiftUI renderer for `:wun/Spacer`. With `:size` set, expands to
// at least that many points in either dimension; without, it's a
// flexible SwiftUI Spacer that absorbs whatever space its parent
// stack offers.

import SwiftUI

public enum WunSpacer {
    public static let render: WunComponent = { props, _ in
        if let size = (props["size"]?.intValue).map({ CGFloat($0) }) {
            return AnyView(SwiftUI.Spacer().frame(minWidth: size, minHeight: size))
        }
        return AnyView(SwiftUI.Spacer())
    }
}
