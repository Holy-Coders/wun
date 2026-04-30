// Glue between the SSE / intent infrastructure and SwiftUI. Owns the
// TreeStore (so SwiftUI can observe @Published changes), the
// Registry (so user code can mix in components), and the SSEClient +
// IntentDispatcher (so the wire stays alive while the window is up).
//
// Configured at construction:
//   - WunFoundation.register(into:)  -- :wun/* SwiftUI renderers
//   - WunExample.register(into:)     -- :myapp/Greeting (this package)
//   - dispatcher.install()           -- routes Wun.intentDispatcher
//                                       through this app's POSTs
//   - SSEClient with X-Wun-Capabilities header listing every
//     registered renderer.

import Foundation
import SwiftUI
import Wun
import WunExample

@MainActor
final class AppViewModel: ObservableObject {
    @Published var status: String = "connecting…"

    let store      = TreeStore()
    let registry   = Registry()
    let dispatcher: IntentDispatcher

    private var client: SSEClient?

    init(baseURL: URL = URL(string: "http://localhost:8080")!) {
        // Populate registries.
        WunFoundation.register(into: registry)
        WunExample.register(into: registry)

        // Resolve relative WebFrame URLs against this host.
        Wun.serverBase = baseURL

        // Wire intents through this dispatcher.
        let d = IntentDispatcher(baseURL: baseURL,
                                 onError: { intent, code, _ in
            print("[demo] intent \(intent) -> \(code)")
        })
        d.install()
        self.dispatcher = d

        // Open the SSE stream with caps + format headers.
        let caps = registry.registered()
            .map { "\($0)@1" }
            .joined(separator: ",")

        client = SSEClient(
            url: URL(string: baseURL.appendingPathComponent("wun").absoluteString)!,
            headers: [
                "X-Wun-Capabilities": caps,
                "X-Wun-Format":       "json",
            ],
            onConnected: { [weak self] in
                Task { @MainActor in self?.status = "connected" }
            },
            onDisconnect: { [weak self] error in
                Task { @MainActor in
                    self?.status = error.map { "disconnected: \($0)" } ?? "disconnected"
                }
            },
            onEnvelope: { [weak self] envelope in
                Task { @MainActor in self?.store.apply(envelope) }
            }
        )
        client?.start()
    }
}
