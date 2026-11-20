import SwiftUI

@main
struct SmileMultifabApp: App {
    var body: some Scene {
        WindowGroup {
            WebShellView()
                .ignoresSafeArea()
        }
    }
}
