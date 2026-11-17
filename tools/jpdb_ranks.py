#!/usr/bin/env python3
"""Collect jpdb.io's per-media frequency ranks for the words of a set of novels.

Each entry on a jpdb vocabulary-list page carries the word's JMdict entry id (jpdb vocabulary
ids are JMdict sequence numbers), its spelling, and jpdb's global rank per media type
(Anime, News, Visual Novels, Books, Non-fiction, Web Novels, Live Action, Aozora). We only
need coverage, not counts: a few large novels give tens of thousands of words with their ranks.

Polite: one request per second, pages cached on disk so a rerun resumes.

    python3 tools/jpdb_ranks.py pick  <cache dir> [N novels]   # choose novels from the difficulty list
    python3 tools/jpdb_ranks.py crawl <cache dir>              # fetch their vocabulary lists
    python3 tools/jpdb_ranks.py build <cache dir> <out.tsv>    # merge into entry/spelling/rank TSV
"""
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.request

BASE = "https://jpdb.io"
UA = "Mozilla/5.0 (compatible; personal-wordlist-builder)"
GENRES = ["Anime", "News", "Visual Novels", "Books", "Non-fiction", "Web Novels", "Live Action", "Aozora"]
# what the story app reads like: fiction and dialogue, not news or non-fiction
FICTION = ["Anime", "Visual Novels", "Books", "Web Novels", "Live Action"]
_last = [0.0]


def fetch(cache, path):
    os.makedirs(os.path.join(cache, "pages"), exist_ok=True)
    f = os.path.join(cache, "pages", hashlib.sha1(path.encode()).hexdigest() + ".html")
    if os.path.exists(f):
        return open(f, encoding="utf-8").read()
    wait = 1.0 - (time.time() - _last[0])
    if wait > 0:
        time.sleep(wait)
    for attempt in range(5):
        try:
            req = urllib.request.Request(BASE + path, headers={"User-Agent": UA})
            body = urllib.request.urlopen(req, timeout=60).read().decode("utf-8")
            break
        except Exception as e:  # noqa: BLE001
            print(f"  retry {path}: {e}", file=sys.stderr)
            time.sleep(10 * (attempt + 1))
    else:
        raise SystemExit(f"giving up on {path}")
    _last[0] = time.time()
    open(f, "w", encoding="utf-8").write(body)
    return body


def pick(cache, n):
    novels = []
    for offset in range(0, 1000, 50):
        page = fetch(cache, f"/novel-difficulty-list?offset={offset}")
        for m in re.finditer(r'<h5[^>]*>(.*?)</h5>.*?<th>Unique words</th><td>(\d+)</td>.*?<th>Average difficulty</th><td>(\d+)/100</td>', page, re.S):
            novels.append({"title": html.unescape(m.group(1)), "unique": int(m.group(2)), "difficulty": int(m.group(3))})
        links = re.findall(r'href="(/novel/\d+/[^"/]+)/stats"', page)
        for nv, link in zip(novels[-len(links):], links):
            nv["path"] = link
    # easy-to-mid novels with the most distinct words: the widest everyday fiction vocabulary
    pool = [x for x in novels if "path" in x and x["difficulty"] <= 45]
    pool.sort(key=lambda x: -x["unique"])
    chosen = pool[:n]
    json.dump(chosen, open(os.path.join(cache, "novels.json"), "w"), ensure_ascii=False, indent=1)
    for x in chosen:
        print(f'{x["difficulty"]:3d}/100 {x["unique"]:6d} words  {x["title"]}')
    print(f"{len(chosen)} novels, ~{sum(x['unique'] for x in chosen) // 50} pages to fetch (cached pages skipped)")


ENTRY = re.compile(r'<div class="entry">.*?href="/vocabulary/(\d+)/([^"#]+)#a"(.*?)(?=<div class="entry">|</div></div></div></div>\s*<div class="pagination|$)', re.S)
TOOLTIP = re.compile(r'data-tooltip="([^"]*)"')


def parse(page):
    out = []
    for m in ENTRY.finditer(page):
        vid, spelling, rest = m.group(1), urllib.request.unquote(m.group(2)), m.group(3)
        ranks = {}
        t = TOOLTIP.search(rest[:3000])
        if t:
            text = html.unescape(t.group(1)).replace("\xa0", " ").replace("‑", "-")
            for g in GENRES:
                r = re.search(re.escape(g.replace("Non-fiction", "Non-fiction")) + r": (\d+)", text)
                if r:
                    ranks[g] = int(r.group(1))
        out.append((vid, spelling, ranks))
    return out


def crawl(cache):
    novels = json.load(open(os.path.join(cache, "novels.json")))
    for i, nv in enumerate(novels, 1):
        offset = 0
        while True:
            page = fetch(cache, f'{nv["path"]}/vocabulary-list?offset={offset}')
            n = len(parse(page))
            if n == 0 or "Next page" not in page:
                break
            offset += 50
        print(f"[{i}/{len(novels)}] {nv['title']}: {offset + n} words", file=sys.stderr)


def build(cache, out):
    words = {}
    for name in os.listdir(os.path.join(cache, "pages")):
        for vid, spelling, ranks in parse(open(os.path.join(cache, "pages", name), encoding="utf-8").read()):
            if ranks:
                words[(vid, spelling)] = ranks
    rows = []
    for (vid, spelling), ranks in words.items():
        fic = sorted(ranks[g] for g in FICTION if g in ranks)
        if not fic:
            continue
        rows.append((fic[len(fic) // 2], vid, spelling, ranks))  # median over fiction media
    rows.sort()
    with open(out, "w", encoding="utf-8") as f:
        f.write("# JMdict entry id<TAB>spelling<TAB>rank (median of jpdb.io's Anime, Visual Novels, Books, Web Novels,\n")
        f.write("# Live Action ranks, rounded by jpdb to 100)<TAB>per-media ranks. Collected by tools/jpdb_ranks.py from jpdb.io.\n")
        for rank, vid, spelling, ranks in rows:
            f.write(f"{vid}\t{spelling}\t{rank}\t" + ",".join(f"{g}={ranks[g]}" for g in GENRES if g in ranks) + "\n")
    print(f"{len(rows)} words -> {out}")


if __name__ == "__main__":
    cmd, cache = sys.argv[1], sys.argv[2]
    if cmd == "pick":
        pick(cache, int(sys.argv[3]) if len(sys.argv) > 3 else 15)
    elif cmd == "crawl":
        crawl(cache)
    elif cmd == "build":
        build(cache, sys.argv[3])
    else:
        sys.exit(__doc__)
