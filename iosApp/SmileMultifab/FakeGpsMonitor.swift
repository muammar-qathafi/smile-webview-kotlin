import CoreLocation
import Foundation

/// Stub deteksi lokasi simulasi (setara FakeGpsMonitor Android).
/// iOS 15+: `CLLocation.sourceInformation?.isSimulatedBySoftware`.
final class FakeGpsMonitor: NSObject, CLLocationManagerDelegate {
    static let shared = FakeGpsMonitor()

    private let manager = CLLocationManager()
    private(set) var isSimulated = false

    private override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func start() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            manager.startUpdatingLocation()
        default:
            break
        }
    }

    func stop() {
        manager.stopUpdatingLocation()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse
            || manager.authorizationStatus == .authorizedAlways {
            manager.startUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        if #available(iOS 15.0, *) {
            isSimulated = loc.sourceInformation?.isSimulatedBySoftware == true
                || loc.sourceInformation?.isProducedByAccessory == true
        } else {
            isSimulated = false
        }
        NotificationCenter.default.post(
            name: .smileFakeGpsStateChanged,
            object: nil,
            userInfo: ["simulated": isSimulated]
        )
    }
}

extension Notification.Name {
    static let smileFakeGpsStateChanged = Notification.Name("smileFakeGpsStateChanged")
}
