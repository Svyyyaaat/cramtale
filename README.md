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
After that, the home screen opens on your story, with a *Today* card (what Anki has due, what the story
read today, about how many passages that takes). The vocabulary check, calibration on real text, your
decks, the review log and costs, the prompt and story files, the AnkiDroid connection (with its probe) and
*Settings* (text size, passage length, writing style, API key, new story) are one tap away.

Passages stream in as they're written; the independent checks run side by side and the scribe works in the
background while you read. Waits that can't stream (planning a session, checks, setup) show flashcards from
your Anki queue that the passage won't use: reviews (Show, then Again/Good) and new cards to triage. A new card
you already know: *Already know it* (in its popup or as a flashcard) skips it from then on and lists it in the
review log as would-be suspended, with Undo.

In the story: tap any word (names too) for its reading, your card's meaning and the dictionary's; *Listen*
reads the passage aloud with the phone's Japanese voice; *Reroll* keeps every version (‹ 1/3 ›); words in
earlier passages can be looked up without counting. The passage on screen, its versions and your taps are
saved, so closing the app (or Android closing it) doesn't lose or rewrite it; a failed call says what to do
and offers *Try again*, which finishes the same turn (no second dice roll). A new session (the world moves
off-screen, new beats) starts after three hours away. Prompt files you haven't edited follow app updates.

**Dictionary:** builds from here ship JMdict prebuilt (`tools/build_dict_asset.sh` makes
`app/src/main/assets/jmdict.sqlite.gz`, gitignored); the phone only unpacks it. Builds without it (CI)
download JMdict and build the database on the phone.

**Android checks without a phone** (`./gradlew :core:check :app:testDebugUnitTest`): core is compiled a
second time against Android 8.0's own Java library (minSdk 26; `sdkmanager "platforms;android-26"`), every
regex in the code and data is scanned for what Android's ICU engine rejects, and a Robolectric test runs the
story (setup, styles, turns, reroll, edit, chapter) on Android's libraries through the real Claude SDK
against a local stand-in server. No emulator here (no KVM), so UI and AnkiDroid still need the phone.

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
