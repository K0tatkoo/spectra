"""Reads every Mutopia piece's RDF (licence, instrument, style, MIDI file), one request a second.
Pieces come from the GitHub repo's ftp/ tree; RDFs live on mutopiaproject.org next to the MIDI."""
import json, os, re, sys, time, urllib.request
tree = json.load(open(sys.argv[1]))["tree"]
pieces = set()
for p in tree:
    if p["type"] == "blob" and p["path"].startswith("ftp/") and p["path"].endswith(".ly"):
        d = p["path"].rsplit("/", 1)[0]
        if d.endswith("-lys"):
            d = d.rsplit("/", 1)[0]
        if d.count("/") >= 3:
            pieces.add(d)
out_path = sys.argv[2]
done = {json.loads(l)["dir"] for l in open(out_path)} if os.path.exists(out_path) else set()
out = open(out_path, "a")
print(f"{len(pieces)} piece folders, {len(done)} already read", flush=True)
for i, d in enumerate(sorted(pieces - done), 1):
    name = d.rsplit("/", 1)[1]
    rec = {"dir": d}
    try:
        req = urllib.request.Request(f"https://www.mutopiaproject.org/{d}/{name}.rdf", headers={"User-Agent": "SpectraStemsData/1.0 (+https://github.com/K0tatkoo/spectra)"})
        body = urllib.request.urlopen(req, timeout=60).read().decode("utf-8", "replace")
        for k in ("title", "composer", "licence", "midFile", "for", "style", "id", "lilypondVersion"):
            m = re.search(rf"<mp:{k}>(.*?)</mp:{k}>", body, re.S)
            rec[k] = m.group(1).strip() if m else None
    except Exception as e:
        rec["error"] = f"{type(e).__name__}: {e}"
    out.write(json.dumps(rec) + "\n"); out.flush()
    if i % 100 == 0:
        print(f"{i} read", flush=True)
    time.sleep(1)
print("done", flush=True)
