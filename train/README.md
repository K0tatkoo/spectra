# Synth splitter — training

The Stems page's **Synth** lane comes from a second, small network that runs
behind the stem model (StemgenRT) on the phone. This folder trains it.

## Why it reads vocals *and* other

StemgenRT knows four stems and puts a synth wherever it sounds most at home.
Measured on the app's own model file with synthetic synths (2026-09-30):

| synth, played alone | → vocals | → other | → drums |
|---|---|---|---|
| supersaw lead | **99 %** | 1 % | 0 % |
| screech (distorted, gliding) | **73 %** | 21 % | 5 % |
| supersaw pad | 14 % | **86 %** | 0 % |
| pluck arp | 8 % | 52 % | **40 %** |
| lead, in a mix with pad, kick, bass | **60 %** | 31 % | 7 % |

So the splitter takes StemgenRT's vocals and other stems, and gives back
three lanes: synth, vocals without the synth, other without the synth. The
lanes still add up to the mix. Drums are left alone: a hardstyle kick is
pitched and distorted, and taking synth out of drums would cost more in
kicks-called-synth than it wins in plucks. What counts as synth: **leads and
pads**; synth bass stays in Bass, FX are left out.

## How it works

- **Signal path** (`synthsplit/layout.py`, `reference.py`): a low-delay STFT —
  2048-sample asymmetric analysis window, 1024-sample synthesis window, hop
  512 — so the lanes lag the other stems by 12–23 ms, not 40+. 186 one-bin
  bands up to 4 kHz, 48 wider ones above: 234 band powers per input stem.
- **Network** (`synthsplit/model.py`): log band powers → Linear → 2× GRU(256) →
  Linear → a mask (0–1) per band per input. ~1.0 M parameters, ~1 M
  multiply-adds a frame, 86 frames a second: a few percent of one of the
  phone's middle cores. The app keeps it off the X4, which the stem model
  needs whole.
- **Contract with the app**: the export carries the layout in ONNX metadata;
  `OnnxMaskNet.kt` reads it. `reference.py` is the spec both sides follow:
  the app is tested against a fixture it writes (`tools/make_dsp_fixture.py`
  → `SpectralSplitTest`), the trainer's batched torch version against the
  reference itself (`tests/test_dsp.py`), the exported graph against the
  torch network (`export.py`, and `OnnxMaskNetTest` from the Kotlin side).
  **Change any of those and all of them must move together.**

## Data

| | what | licence | who downloads |
|---|---|---|---|
| **MoisesDB** | 240 real songs, every track labelled; "synth lead" and "synth pad" are the target | CC BY-NC-SA 4.0 — non-commercial, share-alike | **you** (sign-up and terms at [music.ai/research](https://music.ai/research/)), ~149 GB |
| **Slakh2100** | 2,100 MIDI songs rendered with real synth patches, classes per stem | CC BY 4.0 | `~/synthsplit-data/fetch-slakh.sh` streams it from Zenodo (104 GB, keeping only metadata + stems) |
| **MUSDB18-HQ** | 150 songs; only the *vocals* are used, mixed into Slakh songs (nobody sings in Slakh) | non-commercial | the scripts, from Zenodo (22.7 GB, no sign-up) |

The trained weights derive from MoisesDB and MUSDB18-HQ, so treat them as **CC BY-NC-SA 4.0**:
fine for a free app, and the licence notice ships with the model (to do at
release: `THIRD_PARTY_NOTICES.txt` + the Settings credit already names both).

The splitter trains on what StemgenRT *made of* each mix, never on clean
stems alone: `prepare/*` runs the app's exact stem model file (sha 08424ca9…,
pinned in `synthsplit/stemgen.py`) over every song. Training examples
(`synthsplit/data.py`) mix those "real" crops with remixes of ground-truth
stems where a random, drifting share of the synth is routed into the vocals
input — plus levels, bleed, and crops with no synth at all.

Two things about Slakh worth knowing before touching `prepare/slakh.py`:
the Zenodo archive is **shuffled** (a song's files are spread over all 104 GB,
so no song is complete until near the end of the download), and one
connection to Zenodo gets ~4 MB/s — `tools/fetch_parallel.py` uses six and
feeds `tar` in order, ~14 MB/s on the home line.

## One night on the Mac (M1 Pro, MPS)

```bash
tools/overnight.sh     # nohup it; everything lands in ~/synthsplit-data, progress in logs/overnight.log
```

MUSDB18-HQ → its vocals → Slakh songs as their stems land (750 + 60 held out,
90 s each, 60 % with a MUSDB vocal mixed in) → training until about 07:15
(`--hours`) → `evaluate.py` → `export.py` → the export evaluated again → two
held-out MUSDB songs rendered for listening. Measured on the M1 Pro: a step of
64 × 2 s crops is 320 ms on MPS (the GRU's cost follows the crop length, not
the batch, so short crops it is), 555 ms for the final 21 × 6 s ones; the stem
model pass runs at 0.55× real time per core.

### What the first night taught (2026-10-01)

The first run stalled: training SDR kept rising (2.1 → 3.0 dB by step
20 000), held-out SDR sat at +0.3 dB from step 10 000 on. Measured on its
checkpoint, two causes:

- **Overfitting to the 750 training songs**: real crops scored +1.5 dB on
  training songs, +0.7 dB on held-out ones. Hence dropout 0.2, weight decay
  1e-3, a random EQ applied to inputs and target alike, and twice the song
  mixing — and `best.pt`, the checkpoint that did best on held-out songs.
- **Label noise**: General MIDI files SynthStrings 1/2, Synth Voice and
  SynthBrass 1/2 under strings and brass, so they were "not synth" — in 220
  of the 810 songs, and in 4 of the 11 held-out songs without synth, where
  the model heard synth and was scored −6.6 dB for it. `prepare/relabel_slakh.py`
  moves them into the synth target; the mix, and so the stem model's outputs,
  stay as they were.

The ceiling, for scale: a band mask that *knows* the answer scores **+5.2 dB**
on held-out real crops (+5.0 dB per bin — the bands are not what limits it).
On real Slakh songs StemgenRT puts a median 39 % of the synth in vocals and
46 % in other (45 % / 44 % when someone sings): reading only "other" would
miss half of it.

## Running it (Windows desktop, NVIDIA GPU)

```powershell
cd "Claudes Projects\android\Spectra\train"
uv venv --python 3.12; .venv\Scripts\activate
uv pip install torch --index-url https://download.pytorch.org/whl/cu128
uv pip install -r requirements.txt
python -m pytest tests                      # torch DSP = reference, streaming = batch, export round trip
python export.py --contract-fixture         # then commit app/src/test/resources/synthsplit/net-fixture.*

python prepare\moisesdb.py --moisesdb D:\data\moisesdb\moisesdb_v0.1 --out D:\data\synthsplit --workers 8
python prepare\slakh.py --tar "https://zenodo.org/records/4599666/files/slakh2100_flac_redux.tar.gz?download=1" `
    --out D:\data\synthsplit --workers 8 --max-tracks 800

python train.py --data D:\data\synthsplit --out runs\first          # --resume after any stop
python evaluate.py --data D:\data\synthsplit --run runs\first
python export.py --run runs\first --out ..\app\src\main\assets\stems\synth-split.onnx
python evaluate.py --data D:\data\synthsplit --onnx ..\app\src\main\assets\stems\synth-split.onnx
python tools\render_split.py some-hardstyle.flac --onnx ..\app\src\main\assets\stems\synth-split.onnx --out renders\song
```

Estimates, not yet measured: preparing is CPU-bound (the stem model runs
at roughly half real time per core) — about an hour for MoisesDB and one to
two for 800 Slakh tracks on 8 cores, plus the Slakh download. Training
100 k steps of 32 × 4 s should take a few hours on a recent GPU. The prepared
corpus is ~30 GB for MoisesDB and ~40 GB for Slakh (120 s kept per track).

**Is it good enough?** `train.py` and `evaluate.py` score it against three
answers that need no network: no synth, "all of other is synth", and "all of
vocals and other is synth". It must beat all three on held-out songs — then
listen to `render_split.py` on real hardstyle before it goes near the app.
