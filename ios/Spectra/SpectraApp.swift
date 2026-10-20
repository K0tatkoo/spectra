import SwiftUI

/// Spectra for iPhone. The engine is SpectraCore, a Swift port of the Kotlin
/// engine Android and Windows share, checked against it by fixtures; the
/// interface is iOS's own.
@main
struct SpectraApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
        }
    }
}
