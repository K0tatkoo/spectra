"""Lists ccMixter sample uploads tagged with an instrument, CC BY and CC0 only (records, no audio).
One request a second, pages of 5, as ownstems/sources/ccmixter.py does."""
import json, sys, time, urllib.parse, urllib.request
import codecs, http.client, os
http.client._MAXLINE = 1 << 22  # one ccMixter response carries a header line over 64 KB
UA = "SpectraStemsData/1.0 (+https://github.com/K0tatkoo/spectra)"  # as ownstems/sources/ccmixter.py
API = "https://ccmixter.org/api/query"
codecs.register_error("ccm-cp1252", lambda e: (e.object[e.start:e.end].decode("cp1252", errors="replace"), e.end))
def text_of(body): return body.decode("utf-8", "ccm-cp1252")
TAGS = ["guitar", "electric_guitar", "acoustic_guitar", "distortion", "piano", "violin", "cello", "strings",
        "saxophone", "sax", "trumpet", "brass", "horn", "trombone", "flute", "clarinet", "organ", "rhodes",
        "mandolin", "banjo", "ukulele", "harp", "accordion"]
seen = set()
if os.path.exists(sys.argv[1]):  # resume: uploads already listed, and the tag/licence pairs already done
    for line in open(sys.argv[1]):
        seen.add(int(json.loads(line)["upload_id"]))
done = set(open(sys.argv[1] + ".done").read().split()) if os.path.exists(sys.argv[1] + ".done") else set()
out = open(sys.argv[1], "a")
for tag in TAGS:
    for lic in ("by", "zero"):
        if f"{tag}:{lic}" in done:
            continue
        offset = n = 0
        while True:
            url = API + "?" + urllib.parse.urlencode({"f": "json", "tags": f"sample,{tag}", "type": "all", "lic": lic, "limit": 5, "offset": offset})
            for attempt in range(6):  # ccMixter drops TLS handshakes now and then
                try:
                    body = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60).read()
                    break
                except OSError:
                    time.sleep(10 * (attempt + 1))
            else:
                raise RuntimeError(f"gave up on {url}")
            page = json.loads(text_of(body)) if body.strip() else []
            time.sleep(1)
            if not page: break
            offset += 5
            for u in page:
                n += 1
                if int(u["upload_id"]) in seen: continue
                seen.add(int(u["upload_id"])); u["_query_tag"] = tag; out.write(json.dumps(u) + "\n")
        print(f"{tag:16} {lic:5} {n:5} uploads, {len(seen)} distinct so far", flush=True)
        open(sys.argv[1] + ".done", "a").write(f"{tag}:{lic}\n")
