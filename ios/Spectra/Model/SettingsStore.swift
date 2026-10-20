import Foundation
import SpectraCore

/// Settings on disk: one JSON value in UserDefaults.
///
/// Stored values are laid over the defaults before decoding, so a setting added
/// in a later version simply takes its default instead of throwing the whole
/// saved object away, and a setting that was removed is ignored.
struct SettingsStore {
    private let key = "settings.v1"
    private let defaults = UserDefaults.standard

    func load() -> Settings {
        guard let stored = defaults.data(forKey: key),
              let storedObject = try? JSONSerialization.jsonObject(with: stored) as? [String: Any],
              let baseData = try? JSONEncoder().encode(Settings()),
              var merged = try? JSONSerialization.jsonObject(with: baseData) as? [String: Any]
        else { return Settings() }
        merged.merge(storedObject) { _, new in new }
        guard let data = try? JSONSerialization.data(withJSONObject: merged),
              let settings = try? JSONDecoder().decode(Settings.self, from: data)
        else { return Settings() }
        return settings
    }

    func save(_ settings: Settings) {
        if let data = try? JSONEncoder().encode(settings) { defaults.set(data, forKey: key) }
    }
}
