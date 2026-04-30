// One SSE patch envelope from the server. The shape mirrors the
// transit-json envelope used on the web:
//
//   {
//     "patches": [{"op":"replace","path":[],"value":<tree>}, ...],
//     "status":  "ok",
//     "state":   {"counter": 0},
//     "resolves-intent": "uuid-or-null",
//     "error":   null
//   }

import Foundation

public struct Envelope: Decodable, Equatable, Sendable {
    public let patches: [Patch]
    public let status: String
    public let state: JSON?
    public let resolvesIntent: String?
    public let error: JSON?

    enum CodingKeys: String, CodingKey {
        case patches, status, state, error
        case resolvesIntent = "resolves-intent"
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
