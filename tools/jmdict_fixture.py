#!/usr/bin/env python3
"""Cut a small JMdict test fixture out of JMdict_e.gz: every entry with one of the given
spellings, plus the DOCTYPE (so tests exercise the DTD skipping).
    python3 tools/jmdict_fixture.py JMdict_e.gz out.xml 食べる なぜ ..."""
import gzip
import re
import sys

src, out, words = sys.argv[1], sys.argv[2], set(sys.argv[3:])
text = gzip.open(src, "rt", encoding="utf-8").read()
head = text[: text.index("<JMdict>")]
entries = re.findall(r"<entry>.*?</entry>", text, flags=re.S)
keep = [e for e in entries if words & set(re.findall(r"<(?:keb|reb)>([^<]+)</", e))]
with open(out, "w", encoding="utf-8") as f:
    f.write(head + "<JMdict>\n" + "\n".join(keep) + "\n</JMdict>\n")
print(len(keep), "entries")
