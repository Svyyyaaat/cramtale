#!/usr/bin/env python3
"""Export the notes of an Anki .apkg/.colpkg (or a collection file) as JSON lines, the shape
the app reads from AnkiDroid: one note per line with its deck, note type, fields by name and
its cards (ord, template name, type, queue). Read-only: the package is opened from a temp copy.

    python3 tools/export_deck.py my.apkg local/deck.jsonl

Needs `pip install zstandard` for packages exported by Anki 2.1.50+ (collection.anki21b).
"""
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import zipfile


def open_collection(path, tmp):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            if "collection.anki21b" in names:
                import zstandard
                data = zstandard.ZstdDecompressor().stream_reader(z.open("collection.anki21b")).read()
            else:
                name = "collection.anki21" if "collection.anki21" in names else "collection.anki2"
                data = z.read(name)
        out = os.path.join(tmp, "col.sqlite")
        with open(out, "wb") as f:
            f.write(data)
        return out
    out = os.path.join(tmp, "col.sqlite")
    shutil.copy(path, out)
    return out


def main(src, dst):
    tmp = tempfile.mkdtemp()
    try:
        con = sqlite3.connect(open_collection(src, tmp))
        con.create_collation("unicase", lambda a, b: (a.lower() > b.lower()) - (a.lower() < b.lower()))
        tables = {r[0] for r in con.execute("select name from sqlite_master where type='table'")}
        if "notetypes" in tables:  # schema 15+
            types = {mid: name for mid, name in con.execute("select id, name from notetypes")}
            fields = {}
            for mid, name in con.execute("select ntid, name from fields order by ntid, ord"):
                fields.setdefault(mid, []).append(name)
            templates = {(mid, o): name for mid, o, name in con.execute("select ntid, ord, name from templates")}
            decks = {did: name.replace("\x1f", "::") for did, name in con.execute("select id, name from decks")}
        else:  # legacy JSON in col
            models = json.loads(con.execute("select models from col").fetchone()[0])
            types = {int(k): m["name"] for k, m in models.items()}
            fields = {int(k): [f["name"] for f in m["flds"]] for k, m in models.items()}
            templates = {(int(k), t["ord"]): t["name"] for k, m in models.items() for t in m["tmpls"]}
            decks = {int(k): d["name"] for k, d in json.loads(con.execute("select decks from col").fetchone()[0]).items()}
        cards = {}
        for nid, did, ordn, typ, queue in con.execute("select nid, did, ord, type, queue from cards"):
            cards.setdefault(nid, []).append((did, ordn, typ, queue))
        n = 0
        with open(dst, "w", encoding="utf-8") as out:
            for nid, mid, flds, tags in con.execute("select id, mid, flds, tags from notes"):
                cs = cards.get(nid, [])
                if not cs:
                    continue
                names = fields.get(mid, [])
                values = flds.split("\x1f")
                note = {
                    "noteId": nid,
                    "deck": decks.get(cs[0][0], str(cs[0][0])),
                    "noteType": types.get(mid, str(mid)),
                    "fields": {names[i] if i < len(names) else f"Field{i}": v for i, v in enumerate(values)},
                    "tags": tags.strip(),
                    "cards": [{"ord": o, "template": templates.get((mid, o), f"Card {o + 1}"), "type": t, "queue": q}
                              for _d, o, t, q in sorted(cs, key=lambda c: c[1])],
                }
                out.write(json.dumps(note, ensure_ascii=False) + "\n")
                n += 1
        print(f"{n} notes -> {dst}")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
