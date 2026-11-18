import SwiftUI
import PokeDaisyUI

/// The iPad app: the game and the companion side by side (sideways) or one over the other (upright).
@main
struct PokeDaisyApp: App {
    init() {
        PokeDaisyHost.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .ignoresSafeArea()
        }
    }
}
