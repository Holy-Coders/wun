// SwiftUI renderer for `:wun/Image`. `:src` is a URL string;
// `:alt` becomes the accessibility label; `:size` (when set) pins
// both width and height.

import SwiftUI

public enum WunImage {
    public static let render: WunComponent = { props, _ in
        let src  = props["src"]?.stringValue
        let alt  = props["alt"]?.stringValue ?? ""
        let size = (props["size"]?.intValue).map { CGFloat($0) }

        return AnyView(
            Group {
                if let src, let url = URL(string: src) {
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .empty:
                            ProgressView()
                        case .success(let image):
                            image.resizable()
                                 .aspectRatio(contentMode: .fit)
                        case .failure:
                            SwiftUI.Image(systemName: "photo")
                                   .opacity(0.4)
                        @unknown default:
                            EmptyView()
                        }
                    }
                } else {
                    SwiftUI.Image(systemName: "photo").opacity(0.4)
                }
            }
            .accessibilityLabel(alt)
            .applyingSize(size)
        )
    }
}

private extension View {
    @ViewBuilder
    func applyingSize(_ size: CGFloat?) -> some View {
        if let s = size {
            self.frame(width: s, height: s)
        } else {
            self
        }
    }
}
