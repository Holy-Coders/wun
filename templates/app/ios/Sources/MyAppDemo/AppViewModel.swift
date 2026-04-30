// Glue: owns the Registry, TreeStore, SSEClient, IntentDispatcher.
// Mirrors wun-ios-example/Sources/WunDemoMac/AppViewModel.swift in
// minimal form. Add custom :myapp/* renderers via `registry.register(...)`
// alongside the WunFoundation block below.

import Foundation
import SwiftUI
import Wun

@MainActor
final class AppViewModel: ObservableObject {
    static let shared = AppViewModel()

    @Published var status: String = "connecting…"

    let store      = TreeStore()
    let registry   = Registry()
    let dispatcher: IntentDispatcher

    private var client: SSEClient?

    init(baseURL: URL = URL(string: "http://localhost:8080")!) {
        WunFoundation.register(into: registry)
        // -- register your :myapp/* renderers here --
        // registry.register("myapp/Card", MyAppCard.render)

        Wun.serverBase = baseURL

        let storeRef = store
        let d = IntentDispatcher(
            baseURL: baseURL,
            onError: { intent, code, _ in
                print("[myapp] intent \(intent) -> \(code)")
            },
            connIDProvider: { [weak storeRef] in
                MainActor.assumeIsolated { storeRef?.connID }
            })
        d.install()
        self.dispatcher = d

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
