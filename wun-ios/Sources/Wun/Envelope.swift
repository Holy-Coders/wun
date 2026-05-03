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
    public let presentations: [String]?
    public let meta: JSON?
    public let error: JSON?
    /// Wire envelope version negotiated at handshake (1 or 2).
    public let envelopeVersion: Int?
    /// CSRF token bound to this connection's session, sent on the
    /// initial connect frame; the client echoes it on /intent POSTs.
    public let csrfToken: String?
    /// True when this envelope is a backpressure-driven full re-render.
    public let resync: Bool?
    /// Effective theme map: namespaced keyword string -> resolved value.
    public let theme: JSON?

    enum CodingKeys: String, CodingKey {
        case patches, status, state, error, meta, presentations, theme
        case resolvesIntent  = "resolves-intent"
        case connID          = "conn-id"
        case screenStack     = "screen-stack"
        case envelopeVersion = "envelope-version"
        case csrfToken       = "csrf-token"
        case resync          = "resync?"
    }

    public init(patches: [Patch] = [],
                status: String,
                state: JSON? = nil,
                resolvesIntent: String? = nil,
                connID: String? = nil,
                screenStack: [String]? = nil,
                presentations: [String]? = nil,
                meta: JSON? = nil,
                error: JSON? = nil,
                envelopeVersion: Int? = nil,
                csrfToken: String? = nil,
                resync: Bool? = nil,
                theme: JSON? = nil) {
        self.patches = patches
        self.status = status
        self.state = state
        self.resolvesIntent = resolvesIntent
        self.connID = connID
        self.screenStack = screenStack
        self.presentations = presentations
        self.meta = meta
        self.error = error
        self.envelopeVersion = envelopeVersion
        self.csrfToken = csrfToken
        self.resync = resync
        self.theme = theme
    }

    /// Custom decoder so error envelopes (which omit `patches`) decode
    /// successfully against the same shape; we just default to `[]`.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.patches         = try c.decodeIfPresent([Patch].self,    forKey: .patches) ?? []
        self.status          = try c.decode(String.self,              forKey: .status)
        self.state           = try c.decodeIfPresent(JSON.self,       forKey: .state)
        self.resolvesIntent  = try c.decodeIfPresent(String.self,     forKey: .resolvesIntent)
        self.connID          = try c.decodeIfPresent(String.self,     forKey: .connID)
        self.screenStack     = try c.decodeIfPresent([String].self,   forKey: .screenStack)
        self.presentations   = try c.decodeIfPresent([String].self,   forKey: .presentations)
        self.meta            = try c.decodeIfPresent(JSON.self,       forKey: .meta)
        self.error           = try c.decodeIfPresent(JSON.self,       forKey: .error)
        self.envelopeVersion = try c.decodeIfPresent(Int.self,        forKey: .envelopeVersion)
        self.csrfToken       = try c.decodeIfPresent(String.self,     forKey: .csrfToken)
        self.resync          = try c.decodeIfPresent(Bool.self,       forKey: .resync)
        self.theme           = try c.decodeIfPresent(JSON.self,       forKey: .theme)
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
