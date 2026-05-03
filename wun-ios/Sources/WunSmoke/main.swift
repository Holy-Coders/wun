// End-to-end smoke for the wun-ios SSE client + intent dispatcher.
// Connects to the running server, applies received patches into a
// tree mirror, and fires a small sequence of intents so we can watch
// the round-trip flow on stdout.
//
// Run: swift run wun-smoke           (defaults to localhost:8080)
//      swift run wun-smoke <url>     (override SSE URL)

import Foundation
import Wun

setvbuf(stdout, nil, _IONBF, 0)

let baseURL = URL(string: "http://localhost:8080")!

let sseURL: URL = {
    if CommandLine.arguments.count > 1,
       let provided = URL(string: CommandLine.arguments[1]) {
        return provided
    }
    // Native clients on phase 2.G use headers instead of query
    // params; the URL itself is just /wun.
    return URL(string: "http://localhost:8080/wun")!
}()

let capabilityHeaderValue: String =
    "wun/Stack@1,wun/Text@1,wun/Image@1,wun/Button@1,"
    + "wun/Card@1,wun/Avatar@1,wun/Input@1,wun/List@1,"
    + "wun/Spacer@1,wun/ScrollView@1,wun/WebFrame@1"

// Lock-protected mirror so the SSE callback (background queue) can
// mutate state alongside the main thread's intent firings.
final class Mirror: @unchecked Sendable {
    private let lock = NSLock()
    private var tree: JSON = .null
    private var state: JSON = .null

    func apply(_ envelope: Envelope) -> JSON {
        lock.lock(); defer { lock.unlock() }
        if !envelope.patches.isEmpty {
            tree = Diff.apply(tree, envelope.patches)
        }
        if let s = envelope.state { state = s }
        return state
    }
}

let mirror     = Mirror()
let dispatcher = IntentDispatcher(
    baseURL: baseURL,
    onError: { intent, status, error in
        print("[err] intent=\(intent) status=\(status) error=\(String(describing: error))")
    }
)

// Build the request headers. Native clients can set arbitrary headers
// (unlike EventSource on the web), so caps + format + an optional
// session-token resume token all ride here. The session token is
// pulled from UserDefaults; if a previous run logged in, the server
// rehydrates the user's slice during the SSE handshake.
var sseHeaders: [String: String] = [
    "X-Wun-Capabilities": capabilityHeaderValue,
    "X-Wun-Format":       "json",
]
if let token = Persistence.sessionToken(path: "/") {
    sseHeaders["X-Wun-Session"] = token
    print("[smoke] resuming with persisted session token (prefix \(token.prefix(8)))")
}

let client = SSEClient(
    url: sseURL,
    headers: sseHeaders,
    onConnected: { print("[smoke] connected to \(sseURL) (caps via header)") },
    onDisconnect: { error in
        if let e = error { print("[smoke] disconnected: \(e)") }
        else             { print("[smoke] stream ended") }
        exit(0)
    },
    onEnvelope: { envelope in
        let state    = mirror.apply(envelope)
        let ops      = envelope.patches.map { "\($0.op.rawValue)@\($0.path)" }.joined(separator: " ")
        let resolves = envelope.resolvesIntent.map { String($0.prefix(8)) } ?? "-"
        let counter: String
        if case .object(let o) = state, let v = o["counter"]?.intValue {
            counter = "{counter: \(v)}"
        } else {
            counter = "\(state)"
        }
        print("[\(envelope.status)] resolves=\(resolves) ops=[\(ops)] state=\(counter)")
    }
)

client.start()

// Fire a few intents through the real dispatcher so we can verify the
// full client -> server -> SSE-broadcast round trip without needing a
// human to tap a SwiftUI button.
DispatchQueue.global().asyncAfter(deadline: .now() + 1.0) {
    let id1 = dispatcher.dispatch("counter/inc",   [:])
    print("[smoke] dispatched counter/inc id=\(id1.prefix(8))")
}
DispatchQueue.global().asyncAfter(deadline: .now() + 1.5) {
    let id2 = dispatcher.dispatch("counter/by",    ["n": .int(5)])
    print("[smoke] dispatched counter/by 5 id=\(id2.prefix(8))")
}
DispatchQueue.global().asyncAfter(deadline: .now() + 2.0) {
    let id3 = dispatcher.dispatch("counter/by",    ["n": .string("oops")])
    print("[smoke] dispatched counter/by 'oops' (should 400) id=\(id3.prefix(8))")
}
DispatchQueue.global().asyncAfter(deadline: .now() + 2.5) {
    let id4 = dispatcher.dispatch("counter/reset", [:])
    print("[smoke] dispatched counter/reset id=\(id4.prefix(8))")
}

RunLoop.current.run()
