// One SSE patch envelope from the server. The shape mirrors the
// transit-json envelope used on the web:
//
//   {
//     "patches":      [{"op":"replace","path":[],"value":<tree>}, ...],
//     "status":       "ok",
//     "state":        {"counter": 0},
//     "resolves-intent": "uuid-or-null",
//     "conn-id":      "uuid",                 // 6.C navigation
//     "screen-stack": ["counter/main"],       // 6.C navigation
//     "error":        null
//   }

import Foundation

public struct Envelope: Decodable, Equatable, Sendable {
    public let patches: [Patch]
    public let status: String
    public let state: JSON?
    public let resolvesIntent: String?
    public let connID: String?
    public let screenStack: [String]?
    public let meta: JSON?
    public let error: JSON?

    enum CodingKeys: String, CodingKey {
        case patches, status, state, error, meta
        case resolvesIntent = "resolves-intent"
        case connID         = "conn-id"
        case screenStack    = "screen-stack"
    }

    public init(patches: [Patch] = [],
                status: String,
                state: JSON? = nil,
                resolvesIntent: String? = nil,
                connID: String? = nil,
                screenStack: [String]? = nil,
                meta: JSON? = nil,
                error: JSON? = nil) {
        self.patches = patches
        self.status = status
        self.state = state
        self.resolvesIntent = resolvesIntent
        self.connID = connID
        self.screenStack = screenStack
        self.meta = meta
        self.error = error
    }

    /// Custom decoder so error envelopes (which omit `patches`) decode
    /// successfully against the same shape; we just default to `[]`.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.patches        = try c.decodeIfPresent([Patch].self,    forKey: .patches) ?? []
        self.status         = try c.decode(String.self,              forKey: .status)
        self.state          = try c.decodeIfPresent(JSON.self,       forKey: .state)
        self.resolvesIntent = try c.decodeIfPresent(String.self,     forKey: .resolvesIntent)
        self.connID         = try c.decodeIfPresent(String.self,     forKey: .connID)
        self.screenStack    = try c.decodeIfPresent([String].self,   forKey: .screenStack)
        self.meta           = try c.decodeIfPresent(JSON.self,       forKey: .meta)
        self.error          = try c.decodeIfPresent(JSON.self,       forKey: .error)
    }

    /// Decode an envelope from raw JSON bytes.
    public static func decode(_ data: Data) throws -> Envelope {
        try JSONDecoder().decode(Envelope.self, from: data)
    }

    /// Decode an envelope from a UTF-8 string (i.e. an SSE `data:` line).
    public static func decode(_ string: String) throws -> Envelope {
        try decode(Data(string.utf8))
    }
}
