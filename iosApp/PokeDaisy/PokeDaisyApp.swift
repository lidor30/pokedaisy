import SwiftUI
import PokeDaisyUI

extension CoopCryptoKit: IosCoopPrimitives {}

/// The iPad app: the game and the companion side by side (sideways) or one over the other (upright).
@main
struct PokeDaisyApp: App {
    init() {
        PokeDaisyHost.shared.installCoopCrypto(primitives: CoopCryptoKit())
        PokeDaisyHost.shared.installCoopNetwork(tcp: CoopTcp(), discovery: CoopBonjour())
        PokeDaisyHost.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .ignoresSafeArea()
        }
    }
}
