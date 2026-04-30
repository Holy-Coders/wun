// JSON-Patch-flavoured ops the server emits over /wun.
//   {"op":"replace", "path":[],    "value": <tree>}
//   {"op":"insert",  "path":[2],   "value": <node>}
//   {"op":"remove",  "path":[0,1]}
//
// Path elements are integer child indices. `[]` addresses the whole
// tree. Hiccup vectors carry their tag at index 0 and an optional
// props map at index 1; child paths skip those slots, so `[0]` is the
// first child *after* tag/props.

import Foundation

public enum PatchOp: String, Decodable, Sendable {
    case replace
    case insert
    case remove
}

public struct Patch: Decodable, Equatable, Sendable {
    public let op: PatchOp
    public let path: [Int]
    public let value: JSON?

    enum CodingKeys: String, CodingKey {
        case op, path, value
    }
}
