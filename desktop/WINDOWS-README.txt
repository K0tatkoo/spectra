SPECTRA for Windows
===================

A real-time audio analyser: spectrum, per-band meters, spectrogram, BS.1770-4
loudness, goniometer, oscilloscope — and a waveform mode built for the NES
2A03's triangle channel.

Nothing to install. Unzip this folder anywhere and run Spectra.exe. Java is
bundled; there is no separate runtime to fetch and nothing is written outside
the folder except a small settings file (the path is shown at the bottom of the
Display section in the app).


GETTING AUDIO IN
----------------

Pick an input under Source on the right.

To analyse a microphone or a line input, just choose it.

To analyse what is *playing* — an emulator, a music player, a browser tab —
Windows needs a loopback recording endpoint, and this app can only open the
endpoints the sound driver exposes. Look for:

  * "Stereo Mix", "What U Hear", "Wave Out Mix" — built into many Realtek and
    Creative drivers, and usually DISABLED out of the box. Enable it in
    Settings -> Sound -> More sound settings -> Recording -> right-click in the
    empty space -> Show Disabled Devices -> enable Stereo Mix.
  * A virtual cable such as VB-Audio Virtual Cable or VoiceMeeter, if your
    driver has no loopback endpoint at all. Set it as the playback device and
    select its output here.

If nothing suitable exists, the app says so in the Source panel rather than
sitting on a silent input and pretending.

There is also a "Built-in 2A03 demo signal" input. It generates a NES bass line
in software, so you can see the app working — and see what the 2A03 mode is
showing — before wiring up any of the above.


THE NES 2A03 TRIANGLE MODE
--------------------------

Waveform page -> Mode -> "NES 2A03 triangle".

The NES triangle channel is not a triangle wave. It is a 4-bit DAC walking a
fixed 32-step sequence (15,14...0,0...14,15) clocked straight off the CPU, so
its output is a staircase with only sixteen possible levels and a flat top and
bottom. This mode shows that staircase instead of smoothing it away, and holds
it still:

  * The period is measured, then rounded to the nearest value of the chip's
    11-bit timer register, and the *exact* frequency of that timer is used for
    the display. A period taken from a measurement drifts by a fraction of a
    sample every frame and the wave crawls sideways; a period taken from the
    timer grid is a constant for as long as the note is held.
  * The window start is placed by the phase of the fundamental and then by the
    phase of the folded period itself, so every harmonic lines up — not just the
    first. Aligning on the fundamental alone leaves the 31st harmonic, which is
    where the staircase's corners live, forty degrees out.
  * Whole periods are folded together. That cancels anything not harmonically
    locked to the note — the pulse channels, noise, DMC — while leaving the
    staircase untouched, because the staircase repeats exactly.

The readout across the top is the register value a tracker would have written:

    A1   in tune   $3F8 (1016) · 55.00 Hz                            NTSC

and the line underneath says how much of the staircase can survive at this
pitch:

    27.3 samples/step · fold 8 · fit 0.99 · -6.2 dBFS · grid +0.01/1.7 ct

"samples/step" is the number that decides whether any of this is visible. Each
DAC step lasts (timer + 1) CPU cycles, so at 55 Hz a step covers 27 captured
samples and every corner survives; at 440 Hz it covers 3 and the whole thing
arrives as a smooth triangle. This is a bass instrument display, and it says so
when you point it too high.

"fit" is how well what is on screen matches the chip's own wave *after the
fundamental is removed*. That qualifier matters: a plain sine correlates 0.99
with a triangle, so a raw match proves only that something periodic is there.
What is left after the fundamental comes out is the odd-harmonic series and the
step edges, and that is the 2A03's signature.

Some notes on the controls:

  * Region (NTSC / PAL / Dendy) changes the CPU clock, and therefore which timer
    value a given pitch corresponds to. It does not change the tuning error much
    in the bass — down there the timer grid is finer than two cents, so almost
    any frequency lands on some timer. The register value is what changes.
  * Fold depth trades rejection against responsiveness. Rejection improves as
    the square root of the depth. Raise it when the staircase is buried under a
    melody; lower it when the bass line moves faster than the display follows.
  * A melody note that is an exact harmonic of the bass cannot be folded out —
    it repeats at the bass period, so to anything working in the period domain
    it *is* part of the bass. The display correctly shows the sum.
  * "Quantise to the 16 DAC levels" snaps the trace to the levels the chip can
    actually output. "Mark captured samples" draws the individual samples, which
    is where the capture's own time grid becomes visible.

This mode is in the Windows build only for now.


KNOWN LIMITS
------------

  * The Explorer icon, the taskbar icon and the window icon are all in place,
    but the executable is not code-signed, so SmartScreen will warn on first run
    ("More info" -> "Run anyway").
  * System-audio capture depends entirely on the sound driver exposing a
    loopback endpoint; see GETTING AUDIO IN above.
  * 64-bit Windows 10 or later, x64. There is no ARM64 build.
