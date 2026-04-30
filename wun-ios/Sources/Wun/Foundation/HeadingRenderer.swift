// SwiftUI renderer for `:wun/Heading`. Maps `:level` 1-4 to SwiftUI
// font roles. `:wun/Text {:variant :h1}` already covered the heading-1
// case for the spike; `:wun/Heading` is the explicit primitive,
// matching the brief's vocabulary.

import SwiftUI

public enum WunHeading {
    public static let render: WunComponent = { props, children in
        let level = props["level"]?.intValue ?? 2
        let label = WunChildren.flatten(children)
        let font: Font = {
            switch level {
            case 1: return .largeTitle.weight(.bold)
            case 2: return .title.weight(.semibold)
            case 3: return .title2.weight(.semibold)
            default: return .title3.weight(.semibold)
            }
        }()
        return AnyView(SwiftUI.Text(label).font(font))
    }
}
