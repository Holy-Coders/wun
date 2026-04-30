// A small recursive variant covering JSON's six shapes. Used as the
// uniform value type inside Wun trees: prop values, opaque payloads,
// the `state` mirror, and so on. Sticking to a closed enum keeps the
// rest of the framework Equatable + Codable without resorting to
// Any / AnyCodable workarounds.

import Foundation

public indirect enum JSON: Equatable, Sendable {
    case null
    case bool(Bool)
    case int(Int64)
    case double(Double)
    case string(String)
    case array([JSON])
    case object([String: JSON])
}

extension JSON: Decodable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Int64.self) {
            self = .int(value)
        } else if let value = try? container.decode(Double.self) {
            self = .double(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSON].self) {
            self = .array(value)
        } else if let value = try? container.decode([String: JSON].self) {
            self = .object(value)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unrecognised JSON value")
        }
    }
}

extension JSON: Encodable {
    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null:                 try container.encodeNil()
        case .bool(let value):      try container.encode(value)
        case .int(let value):       try container.encode(value)
        case .double(let value):    try container.encode(value)
        case .string(let value):    try container.encode(value)
        case .array(let value):     try container.encode(value)
        case .object(let value):    try container.encode(value)
        }
    }
}

// Convenience accessors for common cases.
public extension JSON {
    var stringValue: String? {
        if case .string(let v) = self { return v }
        return nil
    }

    var intValue: Int64? {
        switch self {
        case .int(let v):    return v
        case .double(let v): return Int64(v)
        default:             return nil
        }
    }

    var boolValue: Bool? {
        if case .bool(let v) = self { return v }
        return nil
    }

    var doubleValue: Double? {
        switch self {
        case .double(let v): return v
        case .int(let v):    return Double(v)
        default:             return nil
        }
    }

    var objectValue: [String: JSON]? {
        if case .object(let v) = self { return v }
        return nil
    }

    var arrayValue: [JSON]? {
        if case .array(let v) = self { return v }
        return nil
    }
}
