// Real intent dispatcher: takes the (intent, params) the renderer
// fired and POSTs a JSON envelope to /intent. The server applies the
// morph and broadcasts the resulting tree change back to *every*
// SSE connection, including this one; the TreeMirror / TreeStore
// already plumbed the receive side, so the round-trip closes itself.
//
// Optimistic prediction on iOS lands in phase 4 alongside SCI; for
// now the UI waits for the server's confirmation patch before
// updating, just like the brief specifies for phase 2.

import Foundation

public final class IntentDispatcher: @unchecked Sendable {
    public typealias OnError = @Sendable (
        _ intent: String,
        _ status: Int,
        _ error: JSON?
    ) -> Void

    /// Closure that returns the current connection id, or nil before
    /// the first SSE envelope has arrived. Lets the dispatcher stay
    /// decoupled from any particular store -- pass `{ store.connID }`
    /// or any other source of truth.
    public typealias ConnIDProvider = @Sendable () -> String?

    private let baseURL: URL
    private let session: URLSession
    private let onError: OnError
    private let connIDProvider: ConnIDProvider

    public init(baseURL: URL,
                session: URLSession = .shared,
                onError: @escaping OnError = { _, _, _ in },
                connIDProvider: @escaping ConnIDProvider = { nil }) {
        self.baseURL = baseURL
        self.session = session
        self.onError = onError
        self.connIDProvider = connIDProvider
    }

    /// Fire `intent` with `params`. Returns the generated intent id;
    /// the caller can use it to correlate against
    /// `Envelope.resolvesIntent` if it cares to track in-flight
    /// intents. Includes the current `conn-id` (if any) in the body so
    /// framework intents like `:wun/navigate` route to the right
    /// connection's screen-stack on the server.
    @discardableResult
    public func dispatch(_ intent: String, _ params: [String: JSON]) -> String {
        let id = UUID().uuidString.lowercased()
        var fields: [String: JSON] = [
            "intent": .string(intent),
            "params": .object(params),
            "id":     .string(id),
        ]
        if let cid = connIDProvider() {
            fields["conn-id"] = .string(cid)
        }
        let body: JSON = .object(fields)

        var request = URLRequest(url: baseURL.appendingPathComponent("intent"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        do {
            request.httpBody = try JSONEncoder().encode(body)
        } catch {
            return id
        }

        let task = session.dataTask(with: request) { [onError] data, response, _ in
            guard
                let http = response as? HTTPURLResponse,
                http.statusCode != 200
            else { return }
            // Decode the error envelope's "error" field for callers.
            var err: JSON?
            if let data {
                if let envelope = try? JSONDecoder().decode(Envelope.self, from: data) {
                    err = envelope.error
                }
            }
            onError(intent, http.statusCode, err)
        }
        task.resume()
        return id
    }

    /// Convenience: push `path` onto the connection's screen-stack.
    /// `path` is matched server-side against each screen's `:path`
    /// (e.g. `"/about"`).
    @discardableResult
    public func navigate(toPath path: String) -> String {
        dispatch("wun/navigate", ["path": .string(path)])
    }

    /// Convenience: push the screen registered under `screenKey`
    /// (e.g. `"app/about"`).
    @discardableResult
    public func navigate(toScreen screenKey: String) -> String {
        dispatch("wun/navigate", ["screen": .string(screenKey)])
    }

    /// Convenience: pop the top of the connection's screen-stack.
    @discardableResult
    public func popScreen() -> String {
        dispatch("wun/pop", [:])
    }

    /// Install this dispatcher as the global one renderers use. Also
    /// wires `Wun.navigate(...)` / `Wun.popScreen()` so screen pushes
    /// emitted by `:wun/Button {:on-press {:intent :wun/navigate ...}}`
    /// reach the server with the right conn-id.
    @MainActor
    public func install() {
        Wun.intentDispatcher = { [weak self] intent, params in
            guard let self else { return }
            _ = self.dispatch(intent, params)
        }
    }
}
