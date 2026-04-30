// Helpers shared by foundational renderers.

import Foundation

public enum WunChildren {
    /// Concatenate any string-like children into a single label.
    /// Useful for components like `:wun/Text` and `:wun/Button` that
    /// take a string body as their child.
    public static func flatten(_ children: [WunNode]) -> String {
        children.compactMap { node -> String? in
            switch node {
            case .text(let s):   return s
            case .number(let n): return formatNumber(n)
            case .bool(let b):   return String(b)
            default:             return nil
            }
        }.joined()
    }

    private static func formatNumber(_ n: Double) -> String {
        if n.truncatingRemainder(dividingBy: 1) == 0,
           abs(n) < Double(Int64.max) {
            return String(Int64(n))
        }
        return String(n)
    }
}
