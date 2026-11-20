import CryptoKit
import Foundation
import Security

/// CryptoKit primitives for the shared Noise IK protocol. The protocol owns the message order and nonces.
final class CoopCryptoKit: NSObject {
    func randomBytes(size: Int32) -> Data? {
        guard size >= 0 else { return nil }
        if size == 0 { return Data() }
        var result = Data(count: Int(size))
        let status = result.withUnsafeMutableBytes { buffer in
            guard let base = buffer.baseAddress else { return errSecAllocate }
            return SecRandomCopyBytes(kSecRandomDefault, buffer.count, base)
        }
        return status == errSecSuccess ? result : nil
    }

    func publicKey(secret_ secret: Data) -> Data? {
        (try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: secret))?.publicKey.rawRepresentation
    }

    func dh(secret: Data, peerPublic_ peerPublic: Data) -> Data? {
        guard let own = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: secret),
              let peer = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublic),
              let shared = try? own.sharedSecretFromKeyAgreement(with: peer) else { return nil }
        let raw = shared.withUnsafeBytes { Data($0) }
        return raw.reduce(UInt8(0), |) == 0 ? nil : raw
    }

    func seal(key: Data, nonce: Data, associated: Data, plain_ plain: Data) -> Data? {
        guard key.count == 32, nonce.count == 12, let iv = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.seal(plain, using: SymmetricKey(data: key), nonce: iv, authenticating: associated)
        else { return nil }
        return box.ciphertext + box.tag
    }

    func open(key: Data, nonce: Data, associated: Data, sealed_ sealed: Data) -> Data? {
        guard key.count == 32, nonce.count == 12, sealed.count >= 16,
              let iv = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.SealedBox(nonce: iv, ciphertext: sealed.dropLast(16), tag: sealed.suffix(16))
        else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: associated)
    }
}
