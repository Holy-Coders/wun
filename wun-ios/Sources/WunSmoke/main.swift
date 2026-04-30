// End-to-end smoke for the wun-ios SSE client. Connects to the
// running phase-2.A server, applies received patches into a local
// JSON tree, and prints a one-line summary of each frame.
//
// Run: swift run wun-smoke           (defaults to localhost:8080)
//      swift run wun-smoke <url>     (override SSE URL)

import Foundation
import Wun

// Disable stdout buffering so output appears in real time even when
// the binary is started under another process and the parent kills
// us before the buffers would flush at exit.
setvbuf(stdout, nil, _IONBF, 0)

let url: URL = {
    if CommandLine.arguments.count > 1,
       let provided = URL(string: CommandLine.arguments[1]) {
        return provided
    }
    return URL(string:
        "http://localhost:8080/wun?fmt=json&caps=wun/Stack@1,wun/Text@1,wun/Button@1,wun/WebFrame@1"
    )!
}()

// A Sendable lock-protected box around our mirror state so the SSE
// callback (which runs on a background queue) can mutate it safely.
final class Mirror: @unchecked Sendable {
    private let lock = NSLock()
    private var tree: JSON = .null
    private var state: JSON = .null

    func apply(_ envelope: Envelope) -> (JSON, JSON) {
        lock.lock(); defer { lock.unlock() }
        if !envelope.patches.isEmpty {
            tree = Diff.apply(tree, envelope.patches)
        }
        if let s = envelope.state { state = s }
        return (tree, state)
    }
}

let mirror = Mirror()

let client = SSEClient(
    url: url,
    onConnected: {
        print("[smoke] connected to \(url)")
    },
    onDisconnect: { error in
        if let e = error {
            print("[smoke] disconnected: \(e)")
        } else {
            print("[smoke] stream ended")
        }
        exit(0)
    },
    onEnvelope: { envelope in
        let (_, state) = mirror.apply(envelope)
        let ops = envelope.patches
            .map { "\($0.op.rawValue)@\($0.path)" }
            .joined(separator: " ")
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

RunLoop.current.run()
