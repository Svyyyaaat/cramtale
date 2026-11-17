# Cramtale

A Japanese roleplay story that is also your Anki review session. The design is in [PLAN.md](PLAN.md).

**Status: step 2 of 4 (story loop, read-only).** The app reads your AnkiDroid collection and never
writes to it: grades are decided and logged, nothing is sent.

## Layout

| Path | What |
| --- | --- |
| `core/` | Plain Kotlin, unit-tested on the JVM: tokenizer (Kuromoji IPADIC), JMdict builder and lookup, deck reader, matcher, calibration |
| `app/` | Android app (Compose): AnkiDroid reader, probe, dictionary setup, calibration screen |
| `core/.../story` | Story engine: prompts, state files, dice, complications, director/writer/checks/scribe, chapters |
| `core/.../review` | Due snapshot from Anki, grading rules, dry-run log and report, review planner, mistake log |
| `core/.../llm` | Claude client (official Java SDK): caching, effort, structured output, refusal fallback, cost log |
| `prompts/`, `config/` | Every prompt and the taste/style/banned-phrase/complication files, plain text; copied into the story folder on first run and editable in the app (Files) |
| `cli/` | The matcher and the story from the command line: build JMdict, deck reports, analyze text, grammar tests, a live story run |
| `tools/export_deck.py` | `.apkg`/`.colpkg` → JSON lines, the same note shape the app reads from AnkiDroid |
| `local/` | Your data (gitignored): `jmdict.sqlite`, `deck.jsonl`, JMdict download |

## Run it

**Phone:** install the debug APK. The first start walks you through setup (progress is saved):
welcome → connect AnkiDroid (read-only) → download the dictionary → read your decks → vocabulary check
(about 80 words; Back undoes a wrong answer) → Claude API key → create the story → pick a style.
After that, the home screen opens on your story; the vocabulary check, calibration on real text, your
decks, the review log and costs, the prompt and story files and the AnkiDroid connection (with its probe)
are all one tap away, and *Run setup again* repeats the walk-through.

**Screens:** `./gradlew :app:recordPaparazziDebug` renders every screen from sample data to
`app/src/test/snapshots/images/` (light and dark).

**Desktop (JVM 17+):**
```
./gradlew :cli:installDist
curl -o local/JMdict_e.gz https://www.edrdg.org/pub/Nihongo/JMdict_e.gz
cli/build/install/cli/bin/cli build-jmdict local/JMdict_e.gz local/jmdict.sqlite
python3 tools/export_deck.py your-export.apkg local/deck.jsonl      # pip install zstandard
# optional, fiction frequency ranks: see core/src/main/resources/freq/SOURCES.md
cli/build/install/cli/bin/cli deck-report --jmdict local/jmdict.sqlite --deck local/deck.jsonl
cli/build/install/cli/bin/cli vocab-check --jmdict local/jmdict.sqlite --deck local/deck.jsonl
cli/build/install/cli/bin/cli analyze --jmdict local/jmdict.sqlite --deck local/deck.jsonl --threshold 6000 text.txt
./gradlew :core:test        # the real-deck tests run when local/ has jmdict.sqlite and deck.jsonl
# a real story run (setup, style picker, three turns) against the API; costs about $0.30–0.50:
ANTHROPIC_API_KEY=... cli/build/install/cli/bin/cli story-smoke --jmdict local/jmdict.sqlite --deck local/deck.jsonl --dir local/smoke
```

## Credits

JMdict © EDRDG, CC BY-SA 4.0. Kuromoji (Apache 2.0) with IPADIC. Frequency ranks from the JPDB v2.2
frequency dictionary (jpdb.io, compiled by Kuuube; see `core/src/main/resources/freq/SOURCES.md`). Test texts: Aozora Bunko (public domain).
