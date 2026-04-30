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

    private let baseURL: URL
    private let session: URLSession
    private let onError: OnError

    public init(baseURL: URL,
                session: URLSession = .shared,
                onError: @escaping OnError = { _, _, _ in }) {
        self.baseURL = baseURL
        self.session = session
        self.onError = onError
    }

    /// Fire `intent` with `params`. Returns the generated intent id;
    /// the caller can use it to correlate against
    /// `Envelope.resolvesIntent` if it cares to track in-flight
    /// intents.
    @discardableResult
    public func dispatch(_ intent: String, _ params: [String: JSON]) -> String {
        let id = UUID().uuidString.lowercased()
        let body: JSON = .object([
            "intent": .string(intent),
            "params": .object(params),
            "id":     .string(id),
        ])

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

    /// Install this dispatcher as the global one renderers use.
    @MainActor
    public func install() {
        Wun.intentDispatcher = { [weak self] intent, params in
            guard let self else { return }
            _ = self.dispatch(intent, params)
        }
    }
}
