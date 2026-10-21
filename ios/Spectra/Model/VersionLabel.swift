import Foundation

/// A version name as it is shown: "2.0.0-beta.1" is the number "2.0.0" with a
/// "BETA 1" badge beside it, "1.5.1" is just the number. The Swift twin of
/// VersionLabel.kt, which the Android header and the Windows top bar use.
struct VersionLabel: Equatable {
    let number: String
    let stage: String?

    init(number: String, stage: String?) {
        self.number = number
        self.stage = stage
    }

    init(_ versionName: String) {
        let name = versionName.trimmingCharacters(in: .whitespaces)
        guard let m = name.wholeMatch(of: /(\d+(?:\.\d+)*)-(alpha|beta|rc)(?:\.(\d+))?/.ignoresCase()) else {
            self.init(number: name, stage: nil)
            return
        }
        let word = m.2.lowercased() == "rc" ? "RC" : m.2.uppercased()
        self.init(number: String(m.1), stage: m.3.map { "\(word) \($0)" } ?? word)
    }

    /// This build's: the whole name tools/release wrote, or the plain number.
    static var current: VersionLabel {
        let info = Bundle.main.infoDictionary ?? [:]
        let name = info["AppVersionName"] as? String ?? info["CFBundleShortVersionString"] as? String ?? ""
        return VersionLabel(name)
    }
}
