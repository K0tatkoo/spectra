"""Spectra's own stem model: the clean-data pipeline (train/STEMS.md).

Everything the model learns from passes through here, and only from files
the provenance ledger lists under an allow-listed licence.
"""

STEMS = ("vocals", "synth", "other", "bass", "drums")
RATE = 44100
