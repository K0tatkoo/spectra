import Testing
import OnnxRuntimeBindings

@Suite struct StemsTests {
    /// The runtime links and starts on this machine.
    @Test func runtimeStarts() throws {
        _ = try ORTEnv(loggingLevel: .warning)
    }
}
