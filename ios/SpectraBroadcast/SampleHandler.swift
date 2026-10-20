import CoreMedia
import ReplayKit

/// Spectra's screen-broadcast extension: iOS's only door to other apps' sound.
///
/// It runs in its own process while the user broadcasts, receives the screen,
/// the microphone and the apps' audio, and passes on only the last — converted
/// to float stereo into the App Group ring the app reads (BroadcastRing). The
/// screen frames are dropped unread; nothing is recorded or sent anywhere.
/// An extension gets about 50 MB, which this never comes near.
final class SampleHandler: RPBroadcastSampleHandler {
    private var ring: BroadcastRing?

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        ring = BroadcastRing(writer: true)
        if ring == nil {
            finishBroadcastWithError(NSError(domain: "com.n3d.spectra.broadcast", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Spectra could not share audio with the app (App Group missing).",
            ]))
        }
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .audioApp, let ring else { return }
        AudioConversion.write(sampleBuffer, to: ring)
    }

    override func broadcastFinished() {
        ring?.markStopped()
        ring = nil
    }
}

/// ReplayKit's app audio arrives as whatever the system mixer had: usually
/// 16-bit integers, interleaved, sometimes big-endian — read from the stream
/// description, never assumed.
enum AudioConversion {
    static func write(_ buffer: CMSampleBuffer, to ring: BroadcastRing) {
        guard let format = CMSampleBufferGetFormatDescription(buffer),
              let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee else { return }
        let frames = CMSampleBufferGetNumSamples(buffer)
        guard frames > 0 else { return }

        var needed = 0
        CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            buffer, bufferListSizeNeededOut: &needed, bufferListOut: nil, bufferListSize: 0,
            blockBufferAllocator: nil, blockBufferMemoryAllocator: nil, flags: 0, blockBufferOut: nil)
        guard needed > 0 else { return }
        let raw = UnsafeMutableRawPointer.allocate(byteCount: needed, alignment: 16)
        defer { raw.deallocate() }
        let list = raw.assumingMemoryBound(to: AudioBufferList.self)
        var block: CMBlockBuffer?
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            buffer, bufferListSizeNeededOut: nil, bufferListOut: list, bufferListSize: needed,
            blockBufferAllocator: nil, blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment, blockBufferOut: &block)
        guard status == noErr else { return }

        let buffers = UnsafeMutableAudioBufferListPointer(list)
        let channels = Int(asbd.mChannelsPerFrame)
        let flags = asbd.mFormatFlags
        let isFloat = flags & kAudioFormatFlagIsFloat != 0
        let bigEndian = flags & kAudioFormatFlagIsBigEndian != 0
        let planar = flags & kAudioFormatFlagIsNonInterleaved != 0
        let bits = Int(asbd.mBitsPerChannel)
        guard channels > 0, bits == 16 || bits == 32 else { return }

        // Where channel c of frame i sits: its buffer and its index in it.
        func locate(_ i: Int, _ c: Int) -> (UnsafeMutableRawPointer?, Int) {
            let ch = min(c, channels - 1)
            return planar ? (buffers[min(ch, buffers.count - 1)].mData, i) : (buffers[0].mData, i * channels + ch)
        }

        ring.write(frames: frames, sampleRate: Int(asbd.mSampleRate)) { i, c in
            let (data, index) = locate(i, c)
            guard let data else { return 0 }
            if bits == 16 {
                var v = data.load(fromByteOffset: index * 2, as: UInt16.self)
                if bigEndian { v = v.byteSwapped }
                return Float(Int16(bitPattern: v)) / 32768
            }
            var v = data.load(fromByteOffset: index * 4, as: UInt32.self)
            if bigEndian { v = v.byteSwapped }
            return isFloat ? Float(bitPattern: v) : Float(Int32(bitPattern: v)) / 2147483648
        }
    }
}
