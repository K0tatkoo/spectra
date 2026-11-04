"""Which licences training data may carry, and how a source's wording maps to one.

The rule (train/STEMS.md): a paid app may only learn from data whose licence
allows commercial use without binding the weights' own licence. So:

- allowed: CC0, the Public Domain Mark, CC BY (any version, ported or not),
  and audio Spectra's own code generates ("OWN");
- refused, with the reason: anything NonCommercial, NoDerivatives,
  ShareAlike, Sampling / Sampling Plus, "research only", custom terms,
  and anything this module does not recognise.

Unknown is refused, never guessed: `normalize` returns (None, reason) for it.
"""

from __future__ import annotations

import re

ALLOWED = {
    "CC0-1.0": "https://creativecommons.org/publicdomain/zero/1.0/",
    "PDM-1.0": "https://creativecommons.org/publicdomain/mark/1.0/",
    "CC-BY-2.0": "https://creativecommons.org/licenses/by/2.0/",
    "CC-BY-2.5": "https://creativecommons.org/licenses/by/2.5/",
    "CC-BY-3.0": "https://creativecommons.org/licenses/by/3.0/",
    "CC-BY-4.0": "https://creativecommons.org/licenses/by/4.0/",
    "OWN": "",  # made by Spectra's own generators (synthsplit/procsynth.py, hardstyle.py)
}

# Licences that need a credit line wherever the data is credited.
NEEDS_CREDIT = {"CC-BY-2.0", "CC-BY-2.5", "CC-BY-3.0", "CC-BY-4.0"}

_CC_URL = re.compile(
    r"^(?:https?://)?(?:www\.)?creativecommons\.org/"
    r"(?P<kind>licenses|publicdomain)/(?P<terms>[a-z+-]+)/(?P<version>[0-9.]+)"
    r"(?:/(?P<port>[a-z]{2}))?(?:/(?:legalcode(?:\.[a-z-]+)?|deed\.[a-z-]+)?)?/?$",
    re.IGNORECASE,
)

_REFUSED_TERMS = {
    "nc": "NonCommercial: not for a paid app",
    "nd": "NoDerivatives: a trained model may be a derivative",
    "sa": "ShareAlike: would bind the weights' licence",
    "sampling": "Sampling licence: retired, commercial terms unclear",
    "sampling+": "Sampling Plus: retired, whole-work commercial use not allowed",
    "nc-sampling+": "NonCommercial Sampling Plus: not for a paid app",
}

# Names sources use in their own metadata (Freesound, Zenodo, SPDX ids).
_NAMES = {
    "cc0": "CC0-1.0",
    "cc0-1.0": "CC0-1.0",
    "cc0 1.0": "CC0-1.0",
    "creative commons 0": "CC0-1.0",
    "creative commons zero": "CC0-1.0",
    "creative commons zero v1.0 universal": "CC0-1.0",
    "public domain mark": "PDM-1.0",
    "cc-by-3.0": "CC-BY-3.0",
    "cc by 3.0": "CC-BY-3.0",
    "cc-by-4.0": "CC-BY-4.0",
    "cc by 4.0": "CC-BY-4.0",
    "creative commons attribution 4.0 international": "CC-BY-4.0",
    "creative commons attribution 3.0 unported": "CC-BY-3.0",
    "own": "OWN",
}


def normalize(text: str | None) -> tuple[str | None, str | None]:
    """A source's licence wording or URL -> (allowed id, None) or (None, why it is refused)."""
    if not text or not text.strip():
        return None, "no licence stated"
    t = text.strip()
    key = t.lower()
    if key in _NAMES:
        return _NAMES[key], None
    if key.upper() in ALLOWED:
        return key.upper(), None
    m = _CC_URL.match(t)
    if m:
        kind, terms, version = m.group("kind").lower(), m.group("terms").lower(), m.group("version")
        if kind == "publicdomain":
            if terms == "zero" and version == "1.0":
                return "CC0-1.0", None
            if terms == "mark" and version == "1.0":
                return "PDM-1.0", None
            return None, f"unrecognised public-domain tool: {terms} {version}"
        if terms == "by":
            lid = f"CC-BY-{version}"
            if lid in ALLOWED:
                return lid, None
            return None, f"unrecognised CC BY version: {version}"
        parts = terms.split("-")
        for p in parts:
            if p in ("nc", "nd", "sa"):
                return None, _REFUSED_TERMS[p]
        if terms in _REFUSED_TERMS:
            return None, _REFUSED_TERMS[terms]
        return None, f"unrecognised Creative Commons licence: {terms} {version}"
    low = key
    if "noncommercial" in low or "non-commercial" in low or re.search(r"\bnc\b", low):
        return None, _REFUSED_TERMS["nc"]
    if "noderiv" in low or re.search(r"\bnd\b", low):
        return None, _REFUSED_TERMS["nd"]
    if "sharealike" in low or "share-alike" in low or re.search(r"\bsa\b", low):
        return None, _REFUSED_TERMS["sa"]
    if "sampling" in low:
        return None, _REFUSED_TERMS["sampling+"]
    if "research" in low:
        return None, "research-only terms"
    if low in ("attribution", "creative commons attribution"):
        # Freesound says just "Attribution" and means CC BY 4.0 (or 3.0 for old sounds);
        # the version must come from the licence URL, so refuse the bare word.
        return None, "CC BY without a version: give the licence URL"
    return None, f"unrecognised licence: {t}"


def allowed(licence_id: str | None) -> bool:
    return licence_id in ALLOWED
