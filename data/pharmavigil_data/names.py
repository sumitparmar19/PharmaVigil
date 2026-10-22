"""Drug-name keys: one normalization used both by the RxNav client (Python) and by the Spark joins,
so a FAERS drug string and its cached RxNorm mapping always meet on the same key.

The regular expressions are written to mean the same thing in Python `re` and Java regex
(Spark `regexp_replace`), and a test checks that both produce identical keys.
"""

from __future__ import annotations

import re

# Applied in order: (pattern, replacement).
NAME_KEY_STEPS: tuple[tuple[str, str], ...] = (
    # Bracketed notes such as "(NON-SPECIFIC)" or "[ATORVASTATIN]".
    (r"[\(\[][^\)\]]*[\)\]]", " "),
    # Strengths: "10 MG", "0.5MG/ML", "5%", "1000 IU".
    (r"\b\d+(\.\d+)?\s*(MG|MCG|UG|G|KG|ML|L|IU|UNITS?|MEQ|MMOL|%)(/\s*(ML|L|G|DOSE|ACTUATION|H|HR))?\b", " "),
    (r"\d+(\.\d+)?\s*%", " "),
    # Anything that is not a letter, digit or space.
    (r"[^A-Z0-9 ]", " "),
    # Collapse whitespace.
    (r"\s+", " "),
)

# prod_ai lists several active ingredients separated by a backslash.
INGREDIENT_SEPARATOR = "\\"


def name_key(raw: str | None) -> str | None:
    """Upper-case, strip strengths/punctuation/brackets; None when nothing meaningful remains."""
    if raw is None:
        return None
    text = raw.upper()
    for pattern, repl in NAME_KEY_STEPS:
        text = re.sub(pattern, repl, text)
    text = text.strip()
    return text if len(text) >= 2 else None


def split_ingredients(prod_ai: str | None) -> list[str]:
    """Split a FAERS prod_ai value into its ingredient names."""
    if not prod_ai:
        return []
    return [p.strip() for p in prod_ai.split(INGREDIENT_SEPARATOR) if p.strip()]
