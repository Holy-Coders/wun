// Sample app demonstrating a user-defined component shipped as a
// separate Swift package. Wires together:
//   - the `Wun` framework (SSE client, intent dispatcher, foundational
//     :wun/* renderers, WebFrame fallback)
//   - the `WunExample` library (this package's :myapp/Greeting renderer)
//   - the running wun-server (which has :myapp/Greeting registered as
//     a defcomponent and includes it in :counter/main)
//
// Connects with caps that include both frameworks' components, fires
// a few intents, prints a one-line summary per envelope. The point
// of the smoke is to show the iOS client renders the user component
// natively (no WebFrame substitution) -- proving the brief's
// "component registry: framework code and user code participate
// identically" claim end to end.

import Foundation
import Wun
import WunExample

setvbuf(stdout, nil, _IONBF, 0)

let baseURL = URL(string: "http://localhost:8080")!

// Register both the framework's foundational `:wun/*` set and the
// example's `:myapp/Greeting` into the same shared registry.
let registry = Registry()
WunFoundation.register(into: registry)
WunExample.register(into: registry)

// Build the caps header from registered renderers + each component's
// :since (for now everything's @1).
let caps = registry.registered()
    .map { "\($0)@1" }
    .joined(separator: ",")
print("[example] advertising \(registry.registered().count) components: \(caps)")

let mirror = TreeMirror()
let dispatcher = IntentDispatcher(
    baseURL: baseURL,
    onError: { intent, status, error in
        print("[err] intent=\(intent) status=\(status) error=\(String(describing: error))")
    }
)

let client = SSEClient(
    url: URL(string: "http://localhost:8080/wun")!,
    headers: [
        "X-Wun-Capabilities": caps,
        "X-Wun-Format":       "json",
    ],
    onConnected: { print("[example] connected") },
    onDisconnect: { error in
        if let e = error { print("[example] disconnected: \(e)") }
        else             { print("[example] stream ended") }
        exit(0)
    },
    onEnvelope: { envelope in
        Task { await mirror.apply(envelope) }
        let ops      = envelope.patches
            .map { "\($0.op.rawValue)@\($0.path)" }
            .joined(separator: " ")
        let resolves = envelope.resolvesIntent.map { String($0.prefix(8)) } ?? "-"
        let counter: String
        if case .object(let o) = envelope.state ?? .null,
           let v = o["counter"]?.intValue {
            counter = "{counter: \(v)}"
        } else {
            counter = "\(envelope.state ?? .null)"
        }
        print("[\(envelope.status)] resolves=\(resolves) ops=[\(ops)] state=\(counter)")
    }
)
client.start()

// Fire a couple of intents so we exercise the full round trip.
DispatchQueue.global().asyncAfter(deadline: .now() + 1.0) {
    _ = dispatcher.dispatch("counter/inc", [:])
    print("[example] dispatched counter/inc")
}
DispatchQueue.global().asyncAfter(deadline: .now() + 1.5) {
    _ = dispatcher.dispatch("counter/by", ["n": .int(3)])
    print("[example] dispatched counter/by 3")
}

RunLoop.current.run()
