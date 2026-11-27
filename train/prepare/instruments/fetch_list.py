"""Downloads a list of files (JSON: url, dest, licence, licence_evidence, optional referer, sha1_b32, md5,
bytes, credit) under D:/stems-clean/sources/instruments-raw/, one at a time with `pause` seconds between
files (ccMixter asks not to be overburdened). Checks the published SHA-1 (base32, as ccMixter gives it)
or MD5, records a SHA-256 and the licence evidence per file in manifest.jsonl. Re-runs skip done files."""
import base64, hashlib, json, sys, time, urllib.error, urllib.request
from pathlib import Path
ROOT = Path(r"D:\stems-clean\sources\instruments-raw")
items, pause = json.load(open(sys.argv[1])), float(sys.argv[2])
log = open(sys.argv[3], "a", buffering=1, encoding="utf-8")
def say(m): log.write(time.strftime("%Y-%m-%d %H:%M:%S ") + m + "\n")
ok = failed = skipped = 0; total = 0
for i, p in enumerate(items, 1):
    out = ROOT / p["dest"]; out.parent.mkdir(parents=True, exist_ok=True)
    if Path(str(out) + ".ok").exists():
        skipped += 1; continue
    why = None
    for attempt in range(3):
        try:
            h = {"User-Agent": "SpectraStemsData/1.0 (+https://github.com/K0tatkoo/spectra)"}
            if p.get("referer"): h["Referer"] = p["referer"]
            sha1, sha256, md5, n = hashlib.sha1(), hashlib.sha256(), hashlib.md5(), 0
            with urllib.request.urlopen(urllib.request.Request(p["url"], headers=h), timeout=120) as r:
                if r.headers.get_content_type() == "text/html":
                    why = "an HTML page instead of the file"; break
                with open(str(out) + ".part", "wb") as f:
                    while b := r.read(1 << 20):
                        f.write(b); sha1.update(b); sha256.update(b); md5.update(b); n += len(b)
            if p.get("sha1_b32") and base64.b32encode(sha1.digest()).decode() != p["sha1_b32"]:
                raise IOError("SHA-1 differs from the published one")
            if p.get("md5") and md5.hexdigest() != p["md5"]:
                raise IOError("MD5 differs from the published one")
            Path(str(out) + ".part").replace(out)
            rec = {k: p[k] for k in p if k not in ("dest",)}
            rec.update(file=p["dest"], bytes=n, sha256=sha256.hexdigest(), fetched=time.strftime("%Y-%m-%dT%H:%M:%S"))
            with open(ROOT / "manifest.jsonl", "a", encoding="utf-8") as m: m.write(json.dumps(rec) + "\n")
            Path(str(out) + ".ok").write_text(sha256.hexdigest()); ok += 1; total += n; why = None
            break
        except urllib.error.HTTPError as e:
            why = f"HTTP {e.code}"
            if e.code in (403, 404, 410): break
        except Exception as e:
            why = f"{type(e).__name__}: {e}"
        time.sleep(5 * (attempt + 1))
    if why:
        failed += 1; Path(str(out) + ".part").unlink(missing_ok=True); say(f"[{i}/{len(items)}] {p['dest']}: {why}")
    if i % 50 == 0: say(f"[{i}/{len(items)}] {ok} ok, {failed} failed, {skipped} done before, {total / 1e9:.2f} GB")
    time.sleep(pause)
say(f"finished: {ok} ok, {failed} failed, {skipped} done before, {total / 1e9:.2f} GB")
Path(sys.argv[3] + ".DONE").write_text(time.strftime("%Y-%m-%dT%H:%M:%S"))
