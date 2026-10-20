/// What the platform may do for the stem worker thread. Kept as a protocol so
/// `StemSeparator` stays free of app types and its tests run on the Mac.
///
/// On Android this is where the worker pins itself to the fastest core and asks
/// for a performance hint; iOS has no core pinning, so the workers simply run
/// at `.userInteractive` and an app's hooks are there to observe the timings.
/// Called on the worker threads, hence Sendable.
public protocol StemWorkerHooks: Sendable {
    /// Called once, on the worker thread, before the model loads.
    func onStart()

    /// Called after every call into the model with the time it took, and the
    /// length of audio it covered — 2.9 ms of audio per 128-sample hop.
    func onWork(workNanos: Int64, audioNanos: Int64)

    /// Called once, on the worker thread, as it exits.
    func onStop()
}

public extension StemWorkerHooks {
    func onStart() {}
    func onWork(workNanos: Int64, audioNanos: Int64) {}
    func onStop() {}
}

/// Kotlin's `StemWorkerHooks.NONE`: hooks that do nothing.
public struct NoStemWorkerHooks: StemWorkerHooks {
    public init() {}
}

public extension StemWorkerHooks where Self == NoStemWorkerHooks {
    /// Kotlin's `StemWorkerHooks.NONE`.
    static var none: NoStemWorkerHooks { NoStemWorkerHooks() }
}
