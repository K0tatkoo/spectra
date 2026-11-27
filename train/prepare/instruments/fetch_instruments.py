"""Downloads the instrument sources in plan.json into D:/stems-clean/sources/instruments-raw/<group>/<name>/,
one at a time: Zenodo files are checked against their MD5, every file gets a SHA-256, and manifest.jsonl
records url, licence, where the licence was read, size and hash. Re-running skips finished files."""
import hashlib, json, sys, time, urllib.request
from pathlib import Path
ROOT = Path(r"D:\stems-clean\sources\instruments-raw")
plan = json.load(open(sys.argv[1]))
log = open(r"D:\stems-clean\logs\instruments-fetch.log", "a", buffering=1, encoding="utf-8")
def say(m): log.write(time.strftime("%Y-%m-%d %H:%M:%S ") + m + "\n")
done_mb = 0.0
for i, p in enumerate(plan, 1):
    d = ROOT / p["group"].replace(" ", "-") / p["name"]
    d.mkdir(parents=True, exist_ok=True)
    fn = p.get("file") or (f"{p['repo'].split('/')[1]}-{p['branch']}.zip" if p["kind"] == "github" else p["url"].rsplit("/", 1)[1])
    out = d / fn
    if (d / (fn + ".ok")).exists():
        continue
    for attempt in range(1, 4):
        try:
            t0 = time.time(); md5, sha = hashlib.md5(), hashlib.sha256(); n = 0
            req = urllib.request.Request(p["url"], headers={"User-Agent": "stems-data-fetch/1.0"})
            with urllib.request.urlopen(req, timeout=120) as r, open(str(out) + ".part", "wb") as f:
                while True:
                    b = r.read(1 << 20)
                    if not b: break
                    f.write(b); md5.update(b); sha.update(b); n += len(b)
            if p.get("md5") and md5.hexdigest() != p["md5"]:
                raise IOError(f"md5 {md5.hexdigest()} != {p['md5']}")
            if p.get("bytes") and n != p["bytes"]:
                raise IOError(f"size {n} != {p['bytes']}")
            Path(str(out) + ".part").replace(out)
            rec = {k: p[k] for k in ("group", "name", "kind", "url", "licence", "licence_evidence") if k in p}
            rec.update(file=str(out.relative_to(ROOT)), bytes=n, sha256=sha.hexdigest(), fetched=time.strftime("%Y-%m-%dT%H:%M:%S"))
            with open(ROOT / "manifest.jsonl", "a", encoding="utf-8") as m:
                m.write(json.dumps(rec) + "\n")
            (d / (fn + ".ok")).write_text(sha.hexdigest())
            done_mb += n / 1e6
            say(f"[{i}/{len(plan)}] {p['name']}/{fn}: {n / 1e6:.0f} MB in {time.time() - t0:.0f} s ({n / 1e6 / max(time.time() - t0, 1):.1f} MB/s)")
            break
        except Exception as e:
            say(f"[{i}/{len(plan)}] {p['name']}/{fn}: try {attempt} failed: {e}")
            time.sleep(30)
    else:
        say(f"[{i}/{len(plan)}] {p['name']}/{fn}: GAVE UP")
say(f"all done, {done_mb / 1000:.1f} GB this pass")
(ROOT / "FETCH_DONE").write_text(time.strftime("%Y-%m-%dT%H:%M:%S"))
