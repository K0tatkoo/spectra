# Instrument sources for the "instruments" lane (fetched 2026-11-27)

Raw downloads, not yet in the ledger or catalog: they land in
`D:\stems-clean\sources\instruments-raw\`, and `manifest.jsonl` there records
each file's URL, licence, where the licence was read, size and SHA-256.
Licences and verdicts: `train/STEMS.md`, "Instrument sources".

- `libraries-plan.json` + `fetch_instruments.py`: the sample libraries and
  guitar sets (VSCO-2 CE, VCSL, the sfzinstruments CC0/CC BY libraries,
  Salamander, Upright Piano KW, TinySOL, Guitar-TECHS, EGFxSet). Licences come
  from each source's API (Zenodo `metadata.license.id`, GitHub's licence
  detection) or its page (freepats); Zenodo files are checked against their MD5.
- `ccm_list.py` → `ccm_pick.py` → `fetch_list.py`: ccMixter sample uploads
  tagged with an instrument, CC BY and CC0 only, one request a second.
  Remixes and covers are dropped; a file is kept only when its own label (or,
  for a bare "mp3", its file name) names a part, sorted into a family (guitar,
  piano, organ, strings, sax, brass, winds, plucked, reeds, mallets for the
  instruments lane; bass and drums for theirs). Each file is checked against
  the SHA-1 ccMixter publishes and fetched with its upload page as Referer.
- `mutopia_list.py` → `fetch_list.py`: Mutopia's piece list from its GitHub
  repo, each piece's licence from its RDF on mutopiaproject.org; only Public
  Domain and CC BY pieces' MIDI is fetched (share-alike ones are skipped).
