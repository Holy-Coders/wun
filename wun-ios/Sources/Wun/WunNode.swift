// A node in a Wun UI tree. The wire format is Hiccup-shaped:
//   ["wun/Stack", {"gap": 12}, ["wun/Text", {"variant":"h1"}, "..."]]
//
// The first element of an array is the namespaced tag; the second
// (if a dict) is the props map; the rest are children. Strings,
// numbers, and bools are text-like leaves. Everything else passes
// through opaquely so a renderer can decide how (or whether) to
// render unknown shapes.

import Foundation

public indirect enum WunNode: Equatable, Sendable {
    case null
    case text(String)
    case number(Double)
    case bool(Bool)
    case component(tag: String, props: [String: JSON], children: [WunNode])
    case opaque(JSON)
}

public extension WunNode {
    /// Materialise a `JSON` value into the typed `WunNode` tree.
    static func from(_ json: JSON) -> WunNode {
        switch json {
        case .null:
            return .null
        case .bool(let value):
            return .bool(value)
        case .int(let value):
            return .number(Double(value))
        case .double(let value):
            return .number(value)
        case .string(let value):
            return .text(value)
        case .object:
            return .opaque(json)
        case .array(let elements):
            guard case .string(let tag) = elements.first else {
                return .opaque(json)
            }
            var index = 1
            var props: [String: JSON] = [:]
            if index < elements.count, case .object(let propsDict) = elements[index] {
                props = propsDict
                index += 1
            }
            let children = elements[index...].map { WunNode.from($0) }
            return .component(tag: tag, props: props, children: children)
        }
    }

    /// Inverse of `from`: render this node back into the wire's JSON
    /// shape. Useful for diff testing and devtools, not for normal
    /// rendering.
    func toJSON() -> JSON {
        switch self {
        case .null:                                    return .null
        case .bool(let value):                         return .bool(value)
        case .number(let value):                       return .double(value)
        case .text(let value):                         return .string(value)
        case .opaque(let value):                       return value
        case .component(let tag, let props, let kids):
            var out: [JSON] = [.string(tag)]
            if !props.isEmpty {
                out.append(.object(props))
            }
            out.append(contentsOf: kids.map { $0.toJSON() })
            return .array(out)
        }
    }
}
