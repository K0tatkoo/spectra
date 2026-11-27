"""Renders one training part, offline (tools/render_instruments.py runs many): an "instruments" part
made up by ownstems/parts.py or taken from a Mutopia score, or a jungle / breakcore break for drums.

Each job returns its audio (mono; the mixer gives every clip its own width and pan) and a record: what
was played, on which programs, and every component's licence and credit, so the rendered file's ledger
entry can say what it is made of (ownstems/sources/rendered_instruments.py).
"""

from __future__ import annotations

import io
import json
import re
import zipfile
from pathlib import Path

import numpy as np

from . import RATE, breaks, parts, presets
from .amp import amp
from .midi import read_midi

# How often each family plays a genre's part (relative weights).
FAMILY_WEIGHTS = {
    "jazz": {"piano": 3, "sax": 3, "brass": 2.5, "guitar": 2, "strings": 1, "winds": 1, "organ": 1, "mallets": 1,
             "harp": 0.3, "plucked": 0.3, "reeds": 0.5},
    "pop": {"piano": 3, "guitar": 3, "strings": 2, "organ": 0.7, "mallets": 0.7, "plucked": 0.7, "brass": 0.8,
            "winds": 0.8, "sax": 0.8, "reeds": 0.5, "harp": 0.5, "harpsichord": 0.2},
    "rock": {"guitar": 6, "piano": 1.5, "organ": 1.5, "strings": 0.7, "brass": 0.5, "sax": 0.5, "reeds": 0.5,
             "plucked": 0.3, "winds": 0.2, "mallets": 0.1},
    "metal": {"guitar": 8, "strings": 1, "piano": 0.7, "organ": 0.5, "brass": 0.3, "winds": 0.2, "harpsichord": 0.3},
    "breakcore": {"piano": 3, "strings": 2, "guitar": 2, "mallets": 1.5, "harp": 0.5, "brass": 0.7, "winds": 0.7,
                  "sax": 0.5, "plucked": 0.7, "organ": 0.5, "harpsichord": 0.5},
}
# Mutopia's "for" field → the families that may play it
MUTOPIA_FOR = [
    (r"harpsichord|clavichord|virginal", ["harpsichord"]), (r"organ", ["organ"]), (r"piano|keyboard", ["piano"]),
    (r"guitar|lute|vihuela", ["guitar", "plucked"]), (r"harp", ["harp"]), (r"accordion", ["reeds"]),
    (r"violin|viola|cello|string|quartet|trio|fiddle", ["strings"]), (r"flute|oboe|clarinet|bassoon|recorder|wind",
                                                                       ["winds"]),
    (r"horn|trumpet|trombone|brass|tuba", ["brass"]), (r"sax", ["sax"]),
    (r"voice|soprano|alto|tenor|choir|satb|vocal|song", ["winds", "strings", "brass", "organ"]),
]
# General MIDI program → family (None: a track to leave out — basses, drums, synths)
GM_FAMILY = ([("piano",)] * 8 + [("mallets",)] * 8 + [("organ",)] * 5 + [("reeds",)] * 3 + [("guitar",)] * 8
             + [None] * 8 + [("strings",)] * 3 + [None] * 1 + [("strings",)] * 2 + [("harp",)] + [None]
             + [("strings",)] * 4 + [("winds", "strings")] * 4 + [("brass",)] * 8 + [("sax",)] * 4
             + [("winds",)] * 12 + [None] * 24 + [("plucked",)] * 8 + [None] * 16)
assert len(GM_FAMILY) == 128


def licence_of(components: list[tuple[str, str]]) -> tuple[str, str]:
    """One licence for a render from its components' (licence, credit): the CC BY version shared by the
    most components if any is CC BY (every credit kept), else CC0 (public-domain and CC0 parts,
    rendered by our own code)."""
    by = [lic for lic, _ in components if lic.startswith("CC-BY")]
    credit = "; ".join(sorted({c for lic, c in components if c and lic.startswith("CC-BY")}))
    if by:
        return max(set(by), key=by.count), credit
    return "CC0-1.0", ""


class Kits:
    def __init__(self, kit_presets: list[dict]):
        self.presets = kit_presets
        self.cache = {}

    def get(self, rng):
        c = self.presets[int(rng.integers(len(self.presets)))]
        if c["name"] not in self.cache:
            self.cache[c["name"]] = presets.build(c)
        return c, self.cache[c["name"]]


class Renderer:
    def __init__(self, root: Path, inst_presets: list[dict], kit_presets: list[dict], mutopia: list[dict]):
        self.root = Path(root)
        self.presets = inst_presets
        self.by_family: dict[str, list[dict]] = {}
        for c in inst_presets:
            self.by_family.setdefault(c["family"], []).append(c)
        self.kits = Kits(kit_presets)
        self.mutopia = mutopia
        self.cache = {}
        self._lib = None

    def inst(self, c):
        if c["name"] not in self.cache:
            if len(self.cache) > 40:
                self.cache.pop(next(iter(self.cache)))
            self.cache[c["name"]] = presets.build(c)
        return self.cache[c["name"]]

    def pick(self, rng, families, need_keys=None, clean_only=False) -> dict | None:
        fams = [f for f in families if self.by_family.get(f)]
        if not fams:
            return None
        w = np.array([1.0] * len(fams))
        fam = fams[int(rng.choice(len(fams), p=w / w.sum()))]
        pool = self.by_family[fam]
        if clean_only:
            pool = [c for c in pool if not c.get("drive")] or pool
        if need_keys:
            lo, hi = need_keys
            pool = [c for c in pool if c["keys"][0] <= lo + 12 and c["keys"][1] >= hi - 12] or pool
        return pool[int(rng.integers(len(pool)))]

    def pick_family(self, rng, genre: str) -> str:
        fw = {f: w for f, w in FAMILY_WEIGHTS[genre].items() if self.by_family.get(f)}
        names, w = list(fw), np.array(list(fw.values()), float)
        return names[int(rng.choice(len(names), p=w / w.sum()))]

    def _components(self, used: list[dict]) -> list[tuple[str, str]]:
        return [(presets.LIBS[c["lib"]][0], presets.LIBS[c["lib"]][2]) for c in used]

    # --- made-up parts ------------------------------------------------------------------------

    def generated(self, rng, genre: str, seconds: float) -> tuple[np.ndarray, dict]:
        song = parts.new_song(rng, genre, seconds)
        n_inst = 1 if rng.random() < 0.65 else int(rng.integers(2, 4))
        out = np.zeros(int(seconds * RATE), np.float32)
        used, styles = [], []
        for i in range(n_inst):
            fam = self.pick_family(rng, genre)
            c = self.by_family[fam][int(rng.integers(len(self.by_family[fam])))]
            inst = self.inst(c)
            lo, hi = c["keys"]
            notes, style = parts.make_part(rng, song, c["role"], lo, hi, c.get("short", False), c.get("drive", False))
            sw = inst.switches()
            y = inst.render(notes, seconds, rng, int(rng.choice(sw)) if sw and rng.random() < 0.3 else None).mean(0)
            if fam == "guitar" and (genre in ("rock", "metal", "breakcore") and style in ("power", "gallop", "lead")
                                    or c.get("drive")):
                if not c.get("drive") or rng.random() < 0.6:
                    y = amp(y, rng, "high" if genre == "metal" or (genre == "breakcore" and rng.random() < 0.5)
                            else "crunch")
            peak = float(np.max(np.abs(y)))
            if peak < 1e-5:
                continue
            out += y / peak * (10 ** (-rng.uniform(0, 6) / 20) if i else 1.0)
            used.append(c)
            styles.append(f"{fam}:{style}")
        meta = {"kind": "generated", "genre": genre, "bpm": round(song.bpm, 1), "key": song.key, "mode": song.mode,
                "programs": [c["name"] for c in used], "families": sorted({c["family"] for c in used}),
                "styles": styles, "group": used[0]["name"] if used else "none",
                "components": self._components(used)}
        return out, meta

    # --- Mutopia's scores -----------------------------------------------------------------------

    def mutopia_piece(self, rng, rec: dict, seconds: float) -> tuple[np.ndarray, dict] | None:
        path = self.root / "sources" / "instruments-raw" / rec["file"]
        datas = []
        if path.suffix.lower() == ".zip":
            with zipfile.ZipFile(path) as z:
                datas = [z.read(n) for n in z.namelist() if n.lower().endswith((".mid", ".midi"))]
        else:
            datas = [path.read_bytes()]
        if not datas:
            return None
        try:
            notes = read_midi(datas[int(rng.integers(len(datas)))])
        except (ValueError, IndexError):
            return None
        notes = [n for n in notes if n.channel != 9]
        if len(notes) < 8:
            return None
        scale = float(rng.uniform(0.85, 1.15))  # tempo
        end = max(n.end for n in notes) / scale
        start = 0.0 if end <= seconds else float(rng.uniform(0, end - seconds))
        for_fams = next((fams for rx, fams in MUTOPIA_FOR if re.search(rx, (rec.get("for") or "").lower())), None)
        tracks: dict[tuple, list] = {}
        for n in notes:
            tracks.setdefault((n.track, n.channel), []).append(n)
        out = np.zeros(int(seconds * RATE), np.float32)
        used, chosen = [], {}  # one program per family within a piece: both hands on one piano
        for key, tn in tracks.items():
            fam_opts = GM_FAMILY[tn[0].program] if len(GM_FAMILY) > tn[0].program else None
            if for_fams and (fam_opts is None or tn[0].program == 0):
                fam_opts = for_fams  # LilyPond writes piano (0) when no instrument is named
            if fam_opts is None:
                continue
            lo, hi = min(n.pitch for n in tn), max(n.pitch for n in tn)
            fam = next((f for f in fam_opts if f in chosen), None)
            c = chosen[fam] if fam else self.pick(rng, list(fam_opts), (lo, hi), clean_only=True)
            if c is None:
                continue
            chosen.setdefault(c["family"], c)
            ev = [(n.start / scale - start, (n.end - n.start) / scale, n.pitch, n.velocity) for n in tn
                  if n.end / scale > start and n.start / scale < start + seconds]
            ev = [(max(0.0, t), d, p, v) for t, d, p, v in ev]
            if not ev:
                continue
            klo, khi = c["keys"]
            shift = 0  # octaves that bring the part into the program's sampled range
            while min(e[2] for e in ev) + shift < klo and max(e[2] for e in ev) + shift + 12 <= khi + 12:
                shift += 12
            while max(e[2] for e in ev) + shift > khi and min(e[2] for e in ev) + shift - 12 >= klo - 12:
                shift -= 12
            ev = [(t, d, p + shift, v) for t, d, p, v in ev]
            y = self.inst(c).render(ev, seconds, rng).mean(0)
            peak = float(np.max(np.abs(y)))
            if peak < 1e-5:
                continue
            out += y / peak * 10 ** (-rng.uniform(0, 4) / 20)
            if c not in used:
                used.append(c)
        if not used:
            return None
        lic = rec["licence"]
        piece_credit = (f"\"{rec.get('title')}\" ({rec.get('composer')}), typeset for the Mutopia Project "
                        f"({rec.get('mutopia_id')}, {rec['url']})")
        comps = self._components(used) + [(lic if lic != "PD" else "PDM-1.0", piece_credit if lic.startswith("CC-BY")
                                           else "")]
        meta = {"kind": "mutopia", "genre": "classical", "piece": rec.get("title"), "composer": rec.get("composer"),
                "mutopia_id": rec.get("mutopia_id"), "style": rec.get("style"), "for": rec.get("for"),
                "tempo_scale": round(scale, 3), "programs": [c["name"] for c in used],
                "families": sorted({c["family"] for c in used}), "group": rec.get("mutopia_id") or rec["file"],
                "components": comps}
        return out, meta

    # --- Guitar-TECHS's dry DI, re-amped ---------------------------------------------------------

    def reamp(self, rng, seconds: float) -> tuple[np.ndarray, dict] | None:
        """A real guitarist's DI take from Guitar-TECHS through the amp model: rock crunch or metal gain."""
        from .sources import guitar_techs as gt
        from .zips import open_zip

        if not hasattr(self, "_di"):
            d = self.root.joinpath("sources", *gt.DIR)
            self._di = []
            for s_ in gt.SETS:
                z = d / f"{s_}.zip"
                if z.exists():
                    with open_zip(z) as zf:
                        self._di += [(str(z), n) for n, kind in gt.members(zf) if kind == "directinput"]
        if not self._di:
            return None
        zpath, member = self._di[int(rng.integers(len(self._di)))]
        import soundfile as sf

        with open_zip(zpath) as zf:
            x, sr = sf.read(io.BytesIO(zf.read(member)), dtype="float32", always_2d=True)
        x = x.mean(1)
        if sr != RATE:
            import soxr

            x = soxr.resample(x, sr, RATE)
        n = int(seconds * RATE)
        if len(x) > n:
            a = int(rng.integers(0, len(x) - n))
            x = x[a:a + n]
        gain = "high" if rng.random() < 0.5 else "crunch"
        y = amp(x, rng, gain)
        group = Path(zpath).stem
        meta = {"kind": "reamp", "genre": "metal" if gain == "high" else "rock", "source": "guitar-techs",
                "clip": member, "gain": gain, "families": ["guitar"], "programs": [f"guitar-techs/{group}"],
                "group": f"guitar-techs/{group}",
                "components": [("CC-BY-4.0", "Guitar-TECHS (Hegel Emmanuel Pedroza Villalobos, Termeh Taheri, "
                                "Wallace Abreu, Ryan Corey, Iran R. Roman, 2025), Zenodo record 14963133")]}
        return y, meta

    # --- breaks -------------------------------------------------------------------------------

    def egmd(self):
        """E-GMD's beat clips and their ledger rows, read from its own catalog and ledger files only (the
        whole catalog in every worker would cost ~0.4 GB each), its licence checked as Library.open does."""
        if not hasattr(self, "_egmd"):
            from .ledger import Artifact, Clip
            from .licences import allowed

            arts = {a.id: a for a in (Artifact(**json.loads(x)) for x in
                                      (self.root / "ledger" / "egmd.jsonl").read_text(encoding="utf-8").splitlines() if x)}
            clips = [Clip(**json.loads(x)) for x in (self.root / "catalog" / "egmd.jsonl").read_text(encoding="utf-8").splitlines() if x]
            self._egmd_arts = {k: a for k, a in arts.items() if allowed(a.licence)}
            self._egmd = [c for c in clips if c.stem == "drums" and c.artifact in self._egmd_arts and c.seconds >= 6
                          and re.search(r"_\d{2,3}_beat_", c.member or c.artifact)]
            self._egmd_zips = {}
        return self._egmd

    def egmd_audio(self, c, seconds: float) -> np.ndarray:
        import soundfile as sf

        from .zips import open_zip

        art = self._egmd_arts[c.artifact]
        z = self._egmd_zips.get(art.id) or self._egmd_zips.setdefault(art.id, open_zip(self.root / art.path))
        with sf.SoundFile(io.BytesIO(z.read(c.member))) as f:
            x = f.read(int(seconds * f.samplerate), dtype="float32", always_2d=True).mean(1)
            sr = f.samplerate
        if sr != RATE:
            import soxr

            x = soxr.resample(x, sr, RATE)
        return x

    def break_part(self, rng, style: str, seconds: float) -> tuple[np.ndarray, dict]:
        bpm = float(rng.uniform(160, 178) if style == "jungle" else rng.uniform(165, 200))
        comps, meta_src = [], {}
        if rng.random() < 0.3:  # a real E-GMD performance, sped up
            pool = self.egmd()
            c = pool[int(rng.integers(len(pool)))]
            src_bpm = float(re.search(r"_(\d{2,3})_beat_", c.member or c.artifact).group(1))
            x = self.egmd_audio(c, min(c.seconds, 12.0))
            ratio = bpm / src_bpm
            while ratio > 2.2:
                ratio /= 2
            brk = breaks.speed(x, max(1.0, ratio))
            a = int(np.argmax(np.abs(brk) > 0.2 * np.abs(brk).max()))
            brk = brk[a:]
            art = self._egmd_arts[c.artifact]
            comps.append((art.licence, art.credit))
            meta_src = {"source": "egmd", "clip": c.member or c.artifact, "source_bpm": src_bpm, "group": "egmd/" + c.group}
            bpm = src_bpm * max(1.0, ratio)
        else:
            kc, kit = self.kits.get(rng)
            rec_bpm = float(rng.uniform(95, 140))
            brk = breaks.programmed(rng, kit, rec_bpm, 4)
            brk = breaks.speed(brk, bpm / rec_bpm)
            comps.append((presets.LIBS[kc["lib"]][0], presets.LIBS[kc["lib"]][2]))
            meta_src = {"source": "kit", "kit": kc["name"], "group": kc["name"]}
        y = breaks.chop(rng, brk, bpm, seconds, wild=style == "breakcore")
        meta = {"kind": "break", "genre": style, "bpm": round(bpm, 1), **meta_src, "components": comps}
        return y, meta


def write_flac(path: Path, y: np.ndarray, rng) -> float:
    import soundfile as sf

    peak = float(np.max(np.abs(y))) + 1e-9
    y = y / peak * 10 ** (-rng.uniform(1, 6) / 20)
    path.parent.mkdir(parents=True, exist_ok=True)
    buf = io.BytesIO()
    sf.write(buf, y.astype(np.float32), RATE, format="FLAC", subtype="PCM_16")
    path.write_bytes(buf.getvalue())
    return len(y) / RATE


def load_mutopia(root: Path) -> list[dict]:
    man = Path(root) / "sources" / "instruments-raw" / "manifest.jsonl"
    return [r for r in map(json.loads, man.read_text(encoding="utf-8").splitlines()) if r.get("source") == "mutopia"]
