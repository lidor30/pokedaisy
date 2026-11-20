import SwiftUI
import UIKit
import PokeDaisyUI

/// Switches between shared application and gameplay controllers. Screen layout belongs to the shared UI.
struct RootView: View {
    @State private var playing: String?
    @State private var failed: String?
    @State private var restarts = 0

    var body: some View {
        Group {
            if let rom = playing {
                ViewControllerView(make: {
                    PokeDaisyHost.shared.gameScreenViewController(romPath: rom, onFailed: {
                        DispatchQueue.main.async { failed = rom; stop() }
                    })
                }).id("\(rom)#\(restarts)")
            } else {
                ViewControllerView(make: {
                    PokeDaisyHost.shared.libraryViewController(failed: failed, onPlay: { rom in
                        failed = nil
                        playing = rom
                    })
                })
            }
        }
        .background(Color.black)
        .onAppear {
            PokeDaisyHost.shared.onCloseRequested = { stop() }
            PokeDaisyHost.shared.onRestartRequested = { restarts += 1 }
        }
    }

    private func stop() {
        PokeDaisyHost.shared.closeGame()
        playing = nil
    }
}

struct ViewControllerView: UIViewControllerRepresentable {
    let make: () -> UIViewController
    func makeUIViewController(context: Context) -> UIViewController { make() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
