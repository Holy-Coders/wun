// SwiftUI renderer for `:wun/Avatar`. Circular surface that prefers
// `:src` (a URL); falls back to `:initials` text if the image is
// missing or fails to load. `:size` controls the diameter.

import SwiftUI

public enum WunAvatar {
    public static let render: WunComponent = { props, _ in
        let src      = props["src"]?.stringValue
        let initials = props["initials"]?.stringValue ?? "?"
        let size     = (props["size"]?.intValue).map { CGFloat($0) } ?? 40

        return AnyView(
            ZStack {
                Circle().fill(Color.gray.opacity(0.2))
                if let s = src, let url = URL(string: s) {
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .success(let image):
                            image.resizable()
                                 .aspectRatio(contentMode: .fill)
                                 .clipShape(Circle())
                        default:
                            SwiftUI.Text(initials)
                                   .font(.system(size: size * 0.4, weight: .semibold))
                        }
                    }
                } else {
                    SwiftUI.Text(initials)
                           .font(.system(size: size * 0.4, weight: .semibold))
                }
            }
            .frame(width: size, height: size)
        )
    }
}
