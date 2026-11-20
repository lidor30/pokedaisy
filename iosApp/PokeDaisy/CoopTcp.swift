import Darwin
import Foundation
import Network
import PokeDaisyUI

/// Raw TCP only. Kotlin owns frame boundaries, encryption and decisions about peers.
final class CoopTcp: IosCoopTcpNative {
    private let queue = DispatchQueue(label: "app.pokedaisy.coop.tcp", qos: .userInitiated)

    private func parameters() -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        return NWParameters(tls: nil, tcp: tcp)
    }

    func listen(
        preferredPort: Int32,
        incoming: @escaping (any IosCoopTcpConnection) -> Void
    ) -> (any IosCoopTcpListening)? {
        guard let rawPort = UInt16(exactly: preferredPort),
              let port = NWEndpoint.Port(rawValue: rawPort),
              let listener = try? NWListener(using: parameters(), on: port)
        else { return nil }
        let waiter = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready, .failed, .cancelled:
                waiter.signal()
            default:
                break
            }
        }
        let listenerQueue = DispatchQueue(label: "app.pokedaisy.coop.listener")
        listener.newConnectionHandler = { [queue] connection in
            queue.async { incoming(CoopTcpConnection(connection: connection, queue: queue)) }
        }
        listener.start(queue: listenerQueue)
        guard waiter.wait(timeout: .now() + 2) == .success, let bound = listener.port else {
            listener.cancel()
            return nil
        }
        return CoopTcpListening(listener: listener, port: Int32(bound.rawValue))
    }

    func connect(
        host: String,
        port: Int32,
        timeoutMs: Int32,
        connected: @escaping ((any IosCoopTcpConnection)?) -> Void
    ) {
        guard let rawPort = UInt16(exactly: port), let target = NWEndpoint.Port(rawValue: rawPort) else {
            connected(nil)
            return
        }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: target, using: parameters())
        let socket = CoopTcpConnection(connection: connection, queue: queue)
        socket.dial(timeoutMs: Int(timeoutMs), connected: connected)
    }

    func addresses() -> [IosCoopLocalAddress] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(first) }
        return sequence(first: first, next: { $0.pointee.ifa_next }).compactMap { entry in
            let row = entry.pointee
            guard row.ifa_flags & UInt32(IFF_UP) != 0, row.ifa_flags & UInt32(IFF_LOOPBACK) == 0,
                  let address = row.ifa_addr else { return nil }
            switch Int32(address.pointee.sa_family) {
            case AF_INET:
                var addr = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
                let data = withUnsafeBytes(of: &addr) { Data($0) }
                guard data[0] == 10 || (data[0] == 172 && (16...31).contains(data[1])) ||
                        (data[0] == 192 && data[1] == 168) else { return nil }
                return IosCoopLocalAddress(ip: data, interfaceIndex: 0, host: data.map(String.init).joined(separator: "."))
            case AF_INET6:
                let native = address.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { $0.pointee }
                var addr = native.sin6_addr
                let data = withUnsafeBytes(of: &addr) { Data($0) }
                guard data[0] & 0xFE == 0xFC else { return nil }
                var printable = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
                guard inet_ntop(AF_INET6, &addr, &printable, socklen_t(printable.count)) != nil else { return nil }
                return IosCoopLocalAddress(ip: data, interfaceIndex: Int32(native.sin6_scope_id),
                                           host: String(cString: printable))
            default:
                return nil
            }
        }.sorted { $0.ip.count < $1.ip.count }
    }
}

private final class CoopTcpListening: IosCoopTcpListening {
    private let listener: NWListener
    let port: Int32

    init(listener: NWListener, port: Int32) {
        self.listener = listener
        self.port = port
    }

    func close() { listener.cancel() }
}

/// All lifecycle and receive callbacks for a connection run on the same queue.
private final class CoopTcpConnection: IosCoopTcpConnection {
    private let connection: NWConnection
    private let queue: DispatchQueue
    private var started = false
    private var finished = false
    private var reading = false
    private var onReceive: ((Data) -> Void)?
    private var onClose: (() -> Void)?
    private var onDial: (((any IosCoopTcpConnection)?) -> Void)?

    init(connection: NWConnection, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
    }

    func dial(timeoutMs: Int, connected: @escaping ((any IosCoopTcpConnection)?) -> Void) {
        queue.async {
            self.onDial = connected
            self.startConnection()
            self.queue.asyncAfter(deadline: .now() + .milliseconds(max(1, timeoutMs))) {
                if self.onDial != nil { self.end() }
            }
        }
    }

    func start(receive: @escaping (Data) -> Void, closed: @escaping () -> Void) {
        queue.async {
            guard !self.finished else {
                closed()
                return
            }
            self.onReceive = receive
            self.onClose = closed
            self.startConnection()
            self.read()
        }
    }

    func send(bytes: Data, complete: @escaping (KotlinBoolean) -> Void) {
        connection.send(content: bytes, completion: .contentProcessed { error in
            complete(KotlinBoolean(bool: error == nil))
        })
    }

    func abort() { queue.async { self.end() } }

    private func startConnection() {
        guard !started else { return }
        started = true
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                let report = self.onDial
                self.onDial = nil
                report?(self)
            case .failed, .cancelled:
                self.end()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func read() {
        guard !reading, !finished else { return }
        reading = true
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, eof, error in
            self.reading = false
            if let data, !data.isEmpty { self.onReceive?(data) }
            if eof || error != nil { self.end() } else { self.read() }
        }
    }

    private func end() {
        guard !finished else { return }
        finished = true
        connection.stateUpdateHandler = nil
        connection.cancel()
        let result = onDial
        let closed = onClose
        onDial = nil
        onClose = nil
        onReceive = nil
        result?(nil)
        closed?()
    }
}
