// JSON-Patch-flavoured ops the server emits over /wun.
//   {"op":"replace",  "path":[],    "value": <tree>}
//   {"op":"insert",   "path":[2],   "value": <node>}
//   {"op":"remove",   "path":[0,1]}
//   {"op":"children", "path":[0],   "order":[
//       {"key":"a","existing?":true},
//       {"key":"b","existing?":false,"value":<node>}
//   ]}
//
// Path elements are integer child indices. `[]` addresses the whole
// tree. Hiccup vectors carry their tag at index 0 and an optional
// props map at index 1; child paths skip those slots, so `[0]` is the
// first child *after* tag/props.
//
// `:children` is the wire-v2 topology op for keyed children. Each
// `order` entry references either an existing child (matched by
// `key`) or carries an inline `value` for newly-inserted keys.

import Foundation

public enum PatchOp: String, Decodable, Sendable {
    case replace
    case insert
    case remove
    case children
}

public struct ChildOrderEntry: Decodable, Equatable, Sendable {
    public let key: JSON
    public let existing: Bool
    public let value: JSON?

    enum CodingKeys: String, CodingKey {
        case key
        case existing = "existing?"
        case value
    }
}

public struct Patch: Decodable, Equatable, Sendable {
    public let op: PatchOp
    public let path: [Int]
    public let value: JSON?
    public let order: [ChildOrderEntry]?

    enum CodingKeys: String, CodingKey {
        case op, path, value, order
    }
}
