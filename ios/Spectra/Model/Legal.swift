import Foundation

/// The pages the App Store listing points at, linked from the app as Apple asks:
/// the privacy policy has to be reachable from inside the app.
enum Legal {
    static let privacyPolicy = URL(string: "https://n3d-store.com/spectra-privacy")!
    static let support = URL(string: "https://n3d-store.com/app-support#spectra")!
}
