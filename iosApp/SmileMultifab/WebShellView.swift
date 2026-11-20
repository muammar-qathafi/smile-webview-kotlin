import SwiftUI

struct WebShellView: View {
    @StateObject private var fakeGps = FakeGpsUIState()

    var body: some View {
        ZStack {
            SmileWebViewRepresentable()
                .background(Color.white)

            if fakeGps.blocked {
                FakeGpsBlockView {
                    FakeGpsMonitor.shared.start()
                    fakeGps.refresh()
                }
                .transition(.opacity)
                .zIndex(10)
            }
        }
        .onAppear {
            FakeGpsMonitor.shared.start()
            fakeGps.refresh()
        }
        .onReceive(NotificationCenter.default.publisher(for: .smileFakeGpsStateChanged)) { note in
            if let simulated = note.userInfo?["simulated"] as? Bool {
                fakeGps.blocked = simulated
            } else {
                fakeGps.refresh()
            }
        }
    }
}

final class FakeGpsUIState: ObservableObject {
    @Published var blocked = false

    func refresh() {
        blocked = FakeGpsMonitor.shared.isSimulated
    }
}

#if DEBUG
struct WebShellView_Previews: PreviewProvider {
    static var previews: some View {
        WebShellView()
    }
}
#endif
