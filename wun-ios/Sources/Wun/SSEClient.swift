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
    private let session: URLSession
    private var task: Task<Void, Never>?

    private let onEnvelope:    OnEnvelope
    private let onConnected:   OnConnected
    private let onDisconnect:  OnDisconnect

    public init(url: URL,
                session: URLSession = .shared,
                onConnected: @escaping OnConnected = {},
                onDisconnect: @escaping OnDisconnect = { _ in },
                onEnvelope: @escaping OnEnvelope) {
        self.url = url
        self.session = session
        self.onConnected = onConnected
        self.onDisconnect = onDisconnect
        self.onEnvelope = onEnvelope
    }

    public func start() {
        guard task == nil else { return }
        task = Task { [weak self] in
            guard let self else { return }
            await self.runLoop()
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

    private func runLoop() async {
        var request = URLRequest(url: url)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("no-cache",          forHTTPHeaderField: "Cache-Control")

        do {
            let (bytes, response) = try await session.bytes(for: request)
            if let http = response as? HTTPURLResponse, http.statusCode != 200 {
                onDisconnect(URLError(.badServerResponse))
                return
            }
            onConnected()
            try await consume(bytes)
        } catch {
            onDisconnect(error)
            return
        }
        onDisconnect(nil)
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
