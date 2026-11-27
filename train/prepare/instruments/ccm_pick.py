"""Picks isolated instrument, bass and drum parts from the ccMixter listing by each file's own label
(ccMixter's per-file nickname), as ownstems/sources/ccmixter.py does for vocals. Upload rules: CC BY or
CC0 only, no non-commercial / AI / remix / cover uploads. File rules: audio only; a label (or, when the
label is only a format, the file name) must name an instrument; whole mixes, vocals, synths, FX and
previews are dropped; of one part in several formats the best is kept (WAV/FLAC over OGG over MP3)."""
import json, re, sys, collections
from pathlib import PurePosixPath
BAD_TAGS = {"non_commercial", "noncommercial", "ai_generated", "ai-generated", "ai", "remix", "cover", "covers"}
AUDIO = {".wav": 3, ".flac": 3, ".aif": 3, ".aiff": 3, ".ogg": 2, ".mp3": 1}
FORMAT_ONLY = re.compile(r"^(hq |lq )?(mp3|flac|wav|ogg|aiff?|zip|alt\w* file format|alternate.*|stems?|sample|samples|audio|file|version \d+|\d+)$")
REJECT = re.compile(r"\bmix|full|\bsong\b|master|instrumental|backing|karaoke|vocal|vox|acap|a cap|pella|voice|\bsing|choir|\brap\b|"
                    r"preview|demo|snippet|synth|\bpads?\b|\barp|moog|808|\bsub\b|\bfx\b|sfx|effect|noise|click|metronome|guide|"
                    r"\bref(erence)?\b|bounce|sidechain|bpm only|ambien|atmos|soundscape|drone|field rec|beat\b|beats\b|loop ?kit")
FAMILIES = [
    ("drums", r"drum|kick|snare|\brack\b|timbale|cowbell|clave|guiro|woodblock|agogo|hi-?hats?|\bhats?\b|overhead|\boh\b|\btoms?\b|cymbal|\bride\b|crash|perc|shaker|tambourine|conga|bongo|cajon|\bbreaks?\b|room mic|\bkit\b"),
    ("bass", r"\bbass(line| guitar| di| amp| gtr)?\b|upright bass|double bass|contrabass|\bp-?bass|fretless"),
    ("guitar", r"guitar|\bgtr|\bgit\b|strum|\briff|acoustic|electric|dobro|slide"),
    ("piano", r"piano|rhodes|wurli|e-?piano|clav|harpsichord"),
    ("organ", r"organ|hammond|leslie"),
    ("strings", r"violin|viola|cello|fiddle|\bstrings?\b|quartet|pizz"),
    ("sax", r"\bsax"),
    ("brass", r"trumpet|trombone|\bhorns?\b|brass|tuba|flugel|cornet"),
    ("winds", r"flute|clarinet|oboe|bassoon|recorder|whistle|piccolo|\bpipes?\b"),
    ("plucked", r"mandolin|banjo|ukulele|\buke\b|\bharp\b|sitar|bouzouki|\blute\b|zither|koto|balalaika"),
    ("reeds", r"accordion|harmonica|melodica|concertina"),
    ("mallets", r"vibraphone|vibes|marimba|xylophone|glockenspiel|celesta|bells?\b"),
]
FAM = [(n, re.compile(r, re.I)) for n, r in FAMILIES]
def lic_ok(url):
    u = (url or "").lower()
    if any(x in u for x in ("-nc", "/nc", "-nd", "/nd", "-sa", "/sa", "sampling", "nc-")):
        return False
    return "/by/" in u or "zero" in u or "publicdomain" in u
def family(text):
    t = text.lower()
    if REJECT.search(t):
        return None
    if re.search(r"bass ?drum", t):
        return "drums"
    for n, rx in FAM:
        if n == "bass" and re.search(r"bassoon", t):
            continue
        if rx.search(t):
            return n
    return None
recs = [json.loads(l) for l in open(sys.argv[1])]
up_drop = collections.Counter(); picked = []; skipped_labels = collections.Counter()
for u in recs:
    tags = {t.strip().lower() for t in (u.get("upload_tags") or "").split(",") if t.strip()}
    if not lic_ok(u.get("license_url")):
        up_drop["licence"] += 1; continue
    if tags & BAD_TAGS:
        up_drop["tag " + sorted(tags & BAD_TAGS)[0]] += 1; continue
    if re.search(r"\bcover\b", str(u.get("upload_name") or ""), re.I):
        up_drop["cover in title"] += 1; continue
    best = {}
    for f in u.get("files") or []:
        name = f.get("file_name") or ""; ext = PurePosixPath(name).suffix.lower()
        if (f.get("file_format_info") or {}).get("media-type") != "audio" or ext not in AUDIO:
            continue
        label = (f.get("file_nicname") or "").strip().lower()
        text = label
        if FORMAT_ONLY.match(label):  # a bare "mp3" may be the whole song: use the file name only if it names a part
            stem = re.sub(r"[_\-.]+", " ", PurePosixPath(name).stem.lower())
            title = re.sub(r"[_\-.]+", " ", (str(u.get("upload_name") or "")).lower())
            text = stem.replace(title, " ") if title else stem
            if not family(text):
                skipped_labels["(format only) " + label] += 1; continue
        fam = family(text)
        if not fam:
            skipped_labels[label] += 1; continue
        key = re.sub(r"\b(mp3|flac|wav|ogg|aiff?|hq|lq|version|format|alt\w*)\b", "", text).strip()
        cand = {"upload_id": int(u["upload_id"]), "family": fam, "label": label, "file": name, "url": f.get("download_url"),
                "bytes": f.get("file_rawsize") or 0, "rank": AUDIO[ext], "licence_url": u.get("license_url"),
                "page": u.get("file_page_url"), "credit": f"\"{str(u.get('upload_name') or '').strip()}\" by {str(u.get('user_name') or '').strip()} (ccMixter, {u.get('file_page_url')})",
                "sha1": f.get("file_extra", {}).get("sha1") if isinstance(f.get("file_extra"), dict) else None}
        if key not in best or cand["rank"] > best[key]["rank"]:
            best[key] = cand
    picked += best.values()
print(f"{len(recs)} uploads listed; dropped: " + ", ".join(f"{k} {v}" for k, v in up_drop.most_common()))
by = collections.defaultdict(lambda: [0, 0, set()])
for p in picked:
    b = by[p["family"]]; b[0] += 1; b[1] += p["bytes"]; b[2].add(p["upload_id"])
for fam, (n, size, ups) in sorted(by.items(), key=lambda x: -x[1][1]):
    print(f"  {fam:8} {n:5} files from {len(ups):4} uploads, {size / 1e9:6.2f} GB")
print(f"  total    {len(picked):5} files, {sum(p['bytes'] for p in picked) / 1e9:.2f} GB")
print("most common labels skipped:", skipped_labels.most_common(25))
json.dump(picked, open(sys.argv[2], "w"), indent=0)
