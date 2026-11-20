import Darwin
import Foundation
import PokeDaisyUI

/// Advertises and resolves the same DNS-SD records as Android.
final class CoopBonjour: IosCoopDiscovery {
    private var active: Discovery?

    func start(name: String, type: String, port: Int32, attributes: [String: String], observer: any IosCoopDiscoveryObserver) {
        DispatchQueue.main.async {
            self.active?.stop()
            self.active = Discovery(name: name, type: type + ".", port: port, attributes: attributes, observer: observer)
        }
    }

    func setAttributes(attributes: [String: String]) {
        DispatchQueue.main.async { self.active?.setAttributes(attributes) }
    }

    func stop() {
        DispatchQueue.main.async {
            self.active?.stop()
            self.active = nil
        }
    }

    private final class Discovery: NSObject, NetServiceDelegate, NetServiceBrowserDelegate {
        private let publisher: NetService
        private let browser = NetServiceBrowser()
        private let observer: any IosCoopDiscoveryObserver
        private var services: [String: NetService] = [:]

        init(name: String, type: String, port: Int32, attributes: [String: String], observer: any IosCoopDiscoveryObserver) {
            self.publisher = NetService(domain: "local.", type: type, name: name, port: port)
            self.observer = observer
            super.init()
            setAttributes(attributes)
            publisher.publish()
            browser.delegate = self
            browser.searchForServices(ofType: type, inDomain: "local.")
        }

        func setAttributes(_ attributes: [String: String]) {
            publisher.setTXTRecord(NetService.data(fromTXTRecord: attributes.mapValues { Data($0.utf8) }))
        }

        func stop() {
            browser.stop()
            browser.delegate = nil
            publisher.stop()
            for service in services.values { discard(service) }
            services.removeAll()
        }

        func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
            let key = identifier(service)
            guard services[key] == nil, service.name != publisher.name else { return }
            services[key] = service
            service.delegate = self
            service.resolve(withTimeout: 8)
        }

        func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
            let key = identifier(service)
            if let held = services.removeValue(forKey: key) {
                discard(held)
                observer.lost(service: key)
            }
        }

        func netServiceDidResolveAddress(_ service: NetService) {
            announce(service, record: service.txtRecordData())
            service.startMonitoring()
        }

        func netService(_ service: NetService, didUpdateTXTRecord data: Data) {
            announce(service, record: data)
        }

        func netService(_ service: NetService, didNotResolve errorDict: [String: NSNumber]) {
            // A later browse result may resolve it again after a network change.
            let key = identifier(service)
            if services.removeValue(forKey: key) != nil {
                discard(service)
                observer.lost(service: key)
            }
        }

        private func identifier(_ service: NetService) -> String { service.name + "." + service.domain }

        private func discard(_ service: NetService) {
            service.delegate = nil
            service.stopMonitoring()
            service.stop()
        }

        private func announce(_ service: NetService, record: Data?) {
            let key = identifier(service)
            guard services[key] === service, let port = Int32(exactly: service.port), port > 0, let record else { return }
            observer.found(
                service: key, port: port, attributes: NetService.dictionary(fromTXTRecord: record),
                addresses: service.addresses?.compactMap(Self.rawAddress) ?? []
            )
        }

        private static func rawAddress(_ data: Data) -> IosCoopLocalAddress? {
            data.withUnsafeBytes { raw in
                guard raw.count >= MemoryLayout<sockaddr>.size else { return nil }
                switch Int32(raw.loadUnaligned(as: sockaddr.self).sa_family) {
                case AF_INET where raw.count >= MemoryLayout<sockaddr_in>.size:
                    var address = raw.loadUnaligned(as: sockaddr_in.self).sin_addr
                    let ip = withUnsafeBytes(of: &address) { Data($0) }
                    return IosCoopLocalAddress(ip: ip, interfaceIndex: 0, host: "")
                case AF_INET6 where raw.count >= MemoryLayout<sockaddr_in6>.size:
                    let native = raw.loadUnaligned(as: sockaddr_in6.self)
                    var address = native.sin6_addr
                    let ip = withUnsafeBytes(of: &address) { Data($0) }
                    return IosCoopLocalAddress(ip: ip, interfaceIndex: Int32(native.sin6_scope_id), host: "")
                default:
                    return nil
                }
            }
        }
    }
}
