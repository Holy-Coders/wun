// Hand-rolled SSE client. URLSession.bytes(for:) gives an async byte
// stream; we group lines into events on blank lines, surface
// `event: patch` frames as decoded `Envelope`s, and let the consumer
// (typically a TreeMirror + dispatcher) handle them.
//
// The SSE spec we honour is the small subset Pedestal emits:
//   `event: <name>\n`     (optional, defaults to "message")
//   `data: <payload>\n`   (one or more)
//   `\n`                  (frame terminator)
//   `: <comment>\n`       (heartbeat / probe; ignored)
//
// We don't implement reconnection ids, retry timing, or last-event-id
// today; phase 2.B-reconnect (TBD) plumbs those.

import Foundation

public final class SSEClient: @unchecked Sendable {
    public typealias OnEnvelope    = @Sendable (Envelope) -> Void
    public typealias OnConnected   = @Sendable () -> Void
    public typealias OnDisconnect  = @Sendable (Error?) -> Void

    private let url: URL
    private let headers: [String: String]
    private let session: URLSession
    private var task: Task<Void, Never>?

    /// Reconnect every time the underlying stream ends or errors. The
    /// runtime applies exponential backoff between attempts (1s, 2s,
    /// 4s, 8s, capped at 30s) and resets after a successful connect
    /// frame survives long enough to be useful. Set to false from
    /// tests that want exactly one connection attempt.
    public var autoReconnect: Bool = true

    private let onEnvelope:    OnEnvelope
    private let onConnected:   OnConnected
    private let onDisconnect:  OnDisconnect

    public init(url: URL,
                headers: [String: String] = [:],
                session: URLSession = .shared,
                onConnected: @escaping OnConnected = {},
                onDisconnect: @escaping OnDisconnect = { _ in },
                onEnvelope: @escaping OnEnvelope) {
        self.url = url
        self.headers = headers
        self.session = session
        self.onConnected = onConnected
        self.onDisconnect = onDisconnect
        self.onEnvelope = onEnvelope
    }

    public func start() {
        guard task == nil else { return }
        task = Task { [weak self] in
            guard let self else { return }
            await self.driveLoop()
        }
    }

    public func stop() {
        task?.cancel()
        task = nil
    }

    deinit {
        task?.cancel()
    }

    // MARK: - Loop

    /// Outer loop: drive `runLoop` once per connect attempt, sleep
    /// between failed attempts with exponential backoff. The brief's
    /// "match / refine / conflict" semantics already cover what
    /// happens when a reconnect lands -- the bootstrap envelope ships
    /// the full server-authoritative tree and pending intents drop.
    private func driveLoop() async {
        var attempt = 0
        while !Task.isCancelled {
            let didConnect = await runLoop()
            if Task.isCancelled || !autoReconnect { return }
            // Reset backoff when the connection lasted long enough to
            // produce at least one successful frame; otherwise the
            // server is reachable but rejecting us, so backoff.
            attempt = didConnect ? 0 : attempt + 1
            let delayMs = backoffDelayMs(attempt)
            try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
        }
    }

    /// Returns true iff we got past the HTTP 200 + onConnected stage
    /// (so the caller can reset backoff).
    @discardableResult
    private func runLoop() async -> Bool {
        var request = URLRequest(url: url)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("no-cache",          forHTTPHeaderField: "Cache-Control")
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }

        var connected = false
        do {
            let (bytes, response) = try await session.bytes(for: request)
            if let http = response as? HTTPURLResponse, http.statusCode != 200 {
                onDisconnect(URLError(.badServerResponse))
                return false
            }
            onConnected()
            connected = true
            try await consume(bytes)
        } catch {
            onDisconnect(error)
            return connected
        }
        onDisconnect(nil)
        return connected
    }

    private func backoffDelayMs(_ attempt: Int) -> Int {
        // 1s, 2s, 4s, 8s, 16s, 30s cap.
        let exp = min(attempt, 5)
        let base = 1_000 * (1 << exp)
        let jitter = Int.random(in: 0...500)
        return min(30_000, base + jitter)
    }

    private func consume(_ bytes: URLSession.AsyncBytes) async throws {
        // Note: we *don't* use bytes.lines here -- AsyncLineSequence
        // collapses empty lines, but SSE absolutely requires them as
        // frame terminators. Roll our own byte-level splitter that
        // yields a String for every \n (or \r\n), including empties.
        var eventName = "message"
        var dataLines: [String] = []
        var current: [UInt8] = []
        current.reserveCapacity(1024)

        for try await byte in bytes {
            guard byte == 0x0A else {       // LF terminates the line
                current.append(byte)
                continue
            }
            // Strip optional CR from CRLF.
            if current.last == 0x0D {
                current.removeLast()
            }
            let line = String(decoding: current, as: UTF8.self)
            current.removeAll(keepingCapacity: true)

            if line.isEmpty {
                if !dataLines.isEmpty {
                    let payload = dataLines.joined(separator: "\n")
                    handleFrame(name: eventName, data: payload)
                }
                eventName = "message"
                dataLines.removeAll(keepingCapacity: true)
            } else if line.hasPrefix(":") {
                // SSE comment / heartbeat / probe -- ignore.
                continue
            } else if let value = stripPrefix(line, "event:") {
                eventName = value.trimmingCharacters(in: .whitespaces)
            } else if let value = stripPrefix(line, "data:") {
                dataLines.append(stripLeadingSpace(value))
            }
        }
    }

    private func handleFrame(name: String, data: String) {
        guard name == "patch" else { return }
        do {
            let envelope = try Envelope.decode(data)
            onEnvelope(envelope)
        } catch {
            // Ignore malformed frames -- the SSE stream stays open
            // and we'll catch up on the next valid envelope.
        }
    }

    private func stripPrefix(_ s: String, _ prefix: String) -> String? {
        guard s.hasPrefix(prefix) else { return nil }
        return String(s.dropFirst(prefix.count))
    }

    private func stripLeadingSpace(_ s: String) -> String {
        if s.hasPrefix(" ") { return String(s.dropFirst()) }
        return s
    }
}
