# jpdb_ranks.tsv

Word frequency ranks from [jpdb.io](https://jpdb.io), collected by `tools/jpdb_ranks.py` from the
public vocabulary lists of four light-novel series (Mushoku Tensei, The Melancholy of Haruhi Suzumiya,
Ascendance of a Bookworm, Kanojo ga Flag wo Oraretara). The series only decide which words are covered;
each word's rank is jpdb's own global rank. The rank used is the median of jpdb's Anime, Visual Novels,
Books, Web Novels and Live Action ranks, so it reflects fiction and dialogue rather than news.

Columns: JMdict entry id, spelling, rank, per-media ranks. jpdb's vocabulary ids are JMdict sequence
numbers, which is how the matcher looks words up.

The file is **not in git**: the data is jpdb's and this repository is public. Build it locally with
`python3 tools/jpdb_ranks.py pick|crawl|build` (writes here); APKs built without it fall back to
JMdict's priority tags.
