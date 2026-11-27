"""The instruments the renderer may play: every program of every sample library fetched for the
"instruments" lane (train/STEMS.md, "Instrument sources"), with its licence, credit, family and the
role the part generators give it.

`candidates(root)` lists them all; tools/instrument_presets.py builds each one, checks it with
sampler.quality (five notes across its range must sound at the right pitch and not be silent;
mallets and bells, whose overtones are not harmonic, only need to sound), corrects a library's
octave naming (sampler.calibrated) and writes the ones that pass to presets.json, which the
renderer reads. A program the simple player can't do justice to (ARIA-only tricks, unreadable
samples) fails the check and is left out, with the reason kept.

Roles: "keys" (chords and arpeggios: pianos, harpsichords, organs, mallets, harps), "mono" (one line:
winds, brass, solo strings, saxophones, harmonicas), "section" (held chords and lines: string sections,
organ pads), "guitar" (strums, picking, riffs), "plucked" (arpeggios: zithers, banjos, lyres).
"""

from __future__ import annotations

import re
from pathlib import Path, PurePosixPath

RAW = ("sources", "instruments-raw")

KINWIE = "SFZ mapping by kinwie (sfzinstruments)"
LIBS = {
    # library folder: (licence, licence URL, credit) — credits only where the licence needs them
    "VSCO-2-CE": ("CC0-1.0", "https://github.com/sgossner/VSCO-2-CE", ""),
    "VCSL": ("CC0-1.0", "https://github.com/sgossner/VCSL", ""),
    "BengtNilsson.HeadroomPiano": ("CC-BY-4.0", "https://github.com/sfzinstruments/BengtNilsson.HeadroomPiano",
                                   f"Headroom Piano by Bengt Nilsson, {KINWIE}"),
    "Osiris_Piano": ("CC0-1.0", "https://github.com/sfzinstruments/Osiris_Piano", ""),
    "karoryfer.scarypiano": ("CC0-1.0", "https://github.com/sfzinstruments/karoryfer.scarypiano", ""),
    "karoryfer.black-and-green-guitars": ("CC0-1.0", "https://github.com/sfzinstruments/karoryfer.black-and-green-guitars", ""),
    "karoryfer.emilyguitar": ("CC0-1.0", "https://github.com/sfzinstruments/karoryfer.emilyguitar", ""),
    "karoryfer.shinyguitar": ("CC0-1.0", "https://github.com/sfzinstruments/karoryfer.shinyguitar", ""),
    "karoryfer.bear-sax": ("CC0-1.0", "https://github.com/sfzinstruments/karoryfer.bear-sax", ""),
    "karoryfer.weresax": ("CC0-1.0", "https://github.com/sfzinstruments/karoryfer.weresax", ""),
    "MTG.SoloSax": ("CC-BY-4.0", "https://github.com/sfzinstruments/MTG.SoloSax",
                    f"MTG Solo Saxophones, Music Technology Group, Universitat Pompeu Fabra; {KINWIE}"),
    "Ixox.Flute": ("CC-BY-4.0", "https://github.com/sfzinstruments/Ixox.Flute", f"Ixox Flute by Xavier Hosxe, {KINWIE}"),
    "karoryfer-bigcat.cello": ("CC0-1.0", "https://github.com/sfzinstruments/karoryfer-bigcat.cello", ""),
    "karoryfer.war-tuba": ("CC0-1.0", "https://github.com/sfzinstruments/karoryfer.war-tuba", ""),
    "aliexpress-erhu": ("CC0-1.0", "https://github.com/sfzinstruments/aliexpress-erhu", ""),
    "hungarian_zither": ("CC0-1.0", "https://github.com/sfzinstruments/hungarian_zither", ""),
    "cithara-barbarica": ("CC0-1.0", "https://github.com/sfzinstruments/cithara-barbarica", ""),
    "karoryfer.string-cyborgs": ("CC0-1.0", "https://github.com/sfzinstruments/karoryfer.string-cyborgs", ""),
    "ganjo": ("CC0-1.0", "https://github.com/sfzinstruments/ganjo", ""),
    "SalamanderGrandPiano": ("CC-BY-3.0", "https://freepats.zenvoid.org/Piano/acoustic-grand-piano.html",
                             "Salamander Grand Piano by Alexander Holm"),
    "UprightPianoKW": ("CC0-1.0", "https://freepats.zenvoid.org/Piano/acoustic-grand-piano.html", ""),
    "TinySOL": ("CC-BY-4.0", "https://zenodo.org/records/3685367",
                "TinySOL (Cella, Ghisi, Lostanlen, Lévy, Fineberg and Maresz, 2020), Zenodo record 3685367"),
    "EGFxSet": ("CC-BY-4.0", "https://zenodo.org/records/7044411",
                "EGFxSet (Hegel Pedroza, Gerardo Meza and Iran R. Roman, 2022), Zenodo record 7044411"),
}
KIT_LIBS = {  # drum kits for the breaks (ownstems/breaks.py), General MIDI keys
    "karoryfer.big-rusty-drums": ("CC0-1.0", "https://github.com/sfzinstruments/karoryfer.big-rusty-drums", ""),
    "karoryfer.swirly-drums": ("CC0-1.0", "https://github.com/sfzinstruments/karoryfer.swirly-drums", ""),
    "karoryfer.unruly-drums": ("CC0-1.0", "https://github.com/sfzinstruments/karoryfer.unruly-drums", ""),
    "virtuosity_drums": ("CC0-1.0", "https://github.com/sfzinstruments/virtuosity_drums", ""),
    "DrumGizmo.DRSKit": ("CC-BY-4.0", "https://github.com/sfzinstruments/DrumGizmo.DRSKit",
                         f"DRSKit by the DrumGizmo team (Lars Muldjord, Bent Bisballe Nyeng), {KINWIE}"),
    "DrumGizmo.MuldjordKit": ("CC-BY-4.0", "https://github.com/sfzinstruments/DrumGizmo.MuldjordKit",
                              f"MuldjordKit by Lars Muldjord (DrumGizmo), {KINWIE}"),
    "WilkinsonAudio.NakedDrums": ("CC-BY-4.0", "https://github.com/sfzinstruments/WilkinsonAudio.NakedDrums",
                                  f"Naked Drums by Wilkinson Audio, {KINWIE}"),
}
LIBS.update(KIT_LIBS)
SFZ_LIBS = {  # family and role of each SFZ library's programs
    "BengtNilsson.HeadroomPiano": ("piano", "keys"), "Osiris_Piano": ("piano", "keys"),
    "karoryfer.scarypiano": ("piano", "keys"), "karoryfer.black-and-green-guitars": ("guitar", "guitar"),
    "karoryfer.emilyguitar": ("guitar", "guitar"), "karoryfer.shinyguitar": ("guitar", "guitar"),
    "karoryfer.bear-sax": ("sax", "mono"), "karoryfer.weresax": ("sax", "mono"), "MTG.SoloSax": ("sax", "mono"),
    "Ixox.Flute": ("winds", "mono"), "karoryfer-bigcat.cello": ("strings", "mono"),
    "karoryfer.war-tuba": ("brass", "mono"), "aliexpress-erhu": ("strings", "mono"),
    "hungarian_zither": ("plucked", "plucked"), "cithara-barbarica": ("plucked", "plucked"),
    "karoryfer.string-cyborgs": ("strings", "section"), "ganjo": ("plucked", "plucked"),
    "SalamanderGrandPiano": ("piano", "keys"), "UprightPianoKW": ("piano", "keys"),
}
# Words in a VSCO-2 / VCSL folder path → (family, role); first match wins. Percussion, releases and
# basses (the bass lane's) are left out.
NAMED_RULES = [
    (r"percussion|membranophone|cymbal|snare|bass drum|timpani|clash|bell tree|wine glass|tubular glock", None),
    (r"contrabass|release|/rel\b|/releases", None),
    (r"tx81z", None),  # FM synth pianos: the synth lane's, not ours
    (r"piano", ("piano", "keys")), (r"harpsichord", ("harpsichord", "keys")), (r"organ", ("organ", "section")),
    (r"marimba|xylo|vibraphone|glock|chimes|tubular bells|balafon|kalimba|mbira|nyunga", ("mallets", "keys")),
    (r"harp", ("harp", "keys")), (r"violin section|viola section|cello section", ("strings", "section")),
    (r"violin|viola|cello", ("strings", "mono")), (r"trumpet|horn|trombone|tuba", ("brass", "mono")),
    (r"saxophone|saxello", ("sax", "mono")), (r"flute|piccolo|oboe|clarinet|bassoon|recorder|ocarina", ("winds", "mono")),
    (r"harmonica", ("reeds", "mono")), (r"dan tranh|strumstick|psaltery", ("plucked", "plucked")),
]
TINYSOL = {  # TinySOL instrument → (family, role); its contrabass is the bass lane's
    "Bass Tuba": ("brass", "mono"), "French Horn": ("brass", "mono"), "Trombone": ("brass", "mono"),
    "Trumpet in C": ("brass", "mono"), "Accordion": ("reeds", "keys"), "Violoncello": ("strings", "mono"),
    "Viola": ("strings", "mono"), "Violin": ("strings", "mono"), "Alto Saxophone": ("sax", "mono"),
    "Bassoon": ("winds", "mono"), "Clarinet in Bb": ("winds", "mono"), "Flute": ("winds", "mono"),
    "Oboe": ("winds", "mono"),
}
SHORT = re.compile(r"stac|spic|pizz|short|staccato|pluck|marcato", re.I)
EGFX_DRIVES = ("BluesDriver", "TubeScreamer", "RAT")


def raw(root: Path, *parts) -> Path:
    return Path(root, *RAW, *parts)


def library_dir(root: Path, lib: str) -> Path:
    for group in ("instruments", "drum-kits"):
        d = raw(root, group, lib)
        if d.exists():
            return d
    raise FileNotFoundError(lib)


def candidates(root: Path, store_names) -> list[dict]:
    """Every program worth trying. `store_names(path)` lists a zip's or folder's files."""
    out = []
    for lib, (family, role) in SFZ_LIBS.items():
        d = library_dir(root, lib)
        ext = d / "extracted"
        src = str(ext) if ext.exists() else str(next(d.glob("*.zip")))
        names = [n for n in store_names(src) if n.lower().endswith(".sfz")]
        for n in names:
            low = n.lower()
            if re.search(r"/(modules|mappings|mapping|data|includes?|common|map)/|_map\.sfz|map_", low):
                continue
            parts = PurePosixPath(n).parts  # programs sit at the top or in Programs/; deeper ones are fragments
            inner = parts[1:-1] if len(parts) > 1 else ()
            if len(inner) > 1 or (len(inner) == 1 and inner[0].lower() != "programs" and not ext.exists()):
                continue
            out.append({"kind": "sfz", "lib": lib, "src": src, "member": n, "family": family, "role": role,
                        "short": bool(SHORT.search(PurePosixPath(n).stem)), "name": f"{lib}/{PurePosixPath(n).stem}"})
    for lib in ("VSCO-2-CE", "VCSL"):
        zp = str(next(library_dir(root, lib).glob("*.zip")))
        folders = {}
        for n in store_names(zp):
            if n.lower().endswith((".wav", ".flac", ".ogg")):
                folders[str(PurePosixPath(n).parent)] = folders.get(str(PurePosixPath(n).parent), 0) + 1
        for f, count in sorted(folders.items()):
            if count < 6:
                continue
            fam = None
            for rx, val in NAMED_RULES:
                if re.search(rx, f.lower()):
                    fam = val
                    break
            if fam is None:
                continue
            out.append({"kind": "named", "lib": lib, "src": zp, "folder": f, "family": fam[0], "role": fam[1],
                        "short": bool(SHORT.search(f.rsplit("/", 1)[-1])), "name": f"{lib}/{f.split('/', 1)[1]}"})
    tdir = library_dir(root, "TinySOL")
    for inst, (family, role) in TINYSOL.items():
        out.append({"kind": "tinysol", "lib": "TinySOL", "src": str(tdir / "extracted"),
                    "meta": str(tdir / "TinySOL_metadata.csv"), "instrument": inst, "family": family, "role": role,
                    "short": False, "name": f"TinySOL/{inst}"})
    edir = library_dir(root, "EGFxSet")
    for z in sorted(edir.glob("*.zip")):
        effect = z.stem
        for pickup in ("Neck", "Middle", "Bridge"):
            out.append({"kind": "egfx", "lib": "EGFxSet", "src": str(z), "effect": effect, "pickup": pickup,
                        "family": "guitar", "role": "guitar", "short": False,
                        "drive": effect in EGFX_DRIVES, "name": f"EGFxSet/{effect}/{pickup}"})
    return out


def build(c: dict):
    """The Instrument a candidate (or a saved preset) describes."""
    from . import sampler as S

    if c["kind"] == "sfz":
        inst = S.load_sfz(c["src"], c["member"], c["name"], [c["family"], c["role"]])
    elif c["kind"] == "named":
        inst = S.from_named_samples(c["src"], c["folder"], c["name"], [c["family"], c["role"]],
                                    release=0.08 if c["short"] else 0.3)
    elif c["kind"] == "tinysol":
        inst = S.from_tinysol(c["meta"], c["src"], c["instrument"], [c["family"], c["role"]])
    else:
        inst = S.from_egfxset(c["src"], c["effect"], c["pickup"], [c["family"], c["role"]])
    shift = c.get("octave_fix", 0)
    if shift:
        for r in inst.regions:
            r.keycenter += shift
            r.lokey = max(0, r.lokey + shift)
            r.hikey = min(127, r.hikey + shift)
    return inst


def kit_candidates(root: Path, store_names) -> list[dict]:
    """Every top-level program of the drum-kit libraries (checked with breaks.kit_ok)."""
    out = []
    for lib in KIT_LIBS:
        src = str(next(library_dir(root, lib).glob("*.zip")))
        for n in store_names(src):
            parts = PurePosixPath(n).parts
            inner = parts[1:-1]
            if not n.lower().endswith(".sfz") or len(inner) > 1 or (inner and inner[0].lower() != "programs"):
                continue
            if re.search(r"_map|map_|mapping|module", n.lower()):
                continue
            out.append({"kind": "sfz", "lib": lib, "src": src, "member": n, "family": "drums", "role": "kit",
                        "short": False, "name": f"{lib}/{PurePosixPath(n).stem}"})
    return out
