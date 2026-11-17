# jpdb_ranks.tsv

Word frequency ranks from the **JPDB v2.2 Frequency Kana** Yomitan dictionary: jpdb.io's corpus of
anime, light novels, visual novels, web novels and drama, compiled by Kuuube
(https://github.com/Kuuuube/yomitan-dictionaries). Fiction and dialogue, not news.

Converted with `cli import-freq`: each (word, reading) row is tied to the JMdict entries with that
spelling and reading, and a kana-written rank (㋕) is stored under the kana spelling.
Columns: JMdict entry id, spelling, rank.

The file is **not in git**: the data is jpdb's and this repository is public. To rebuild it:

    curl -L -o local/jpdb.zip https://github.com/Kuuuube/yomitan-dictionaries/raw/main/dictionaries/JPDB_v2.2_Frequency_Kana_2024-10-13.zip
    cli/build/install/cli/bin/cli import-freq --jmdict local/jmdict.sqlite --out core/src/main/resources/freq/jpdb_ranks.tsv local/jpdb.zip

APKs built without it (e.g. by CI) fall back to JMdict's priority tags.
