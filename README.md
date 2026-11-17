# Cramtale

A Japanese roleplay story that is also your Anki review session. The design is in [PLAN.md](PLAN.md).

**Status: step 1 of 4 (matcher calibration).** The app reads your AnkiDroid collection and never
writes to it.

## Layout

| Path | What |
| --- | --- |
| `core/` | Plain Kotlin, unit-tested on the JVM: tokenizer (Kuromoji IPADIC), JMdict builder and lookup, deck reader, matcher, calibration |
| `app/` | Android app (Compose): AnkiDroid reader, probe, dictionary setup, calibration screen |
| `cli/` | The matcher from the command line: build JMdict, report on a deck export, analyze text |
| `tools/export_deck.py` | `.apkg`/`.colpkg` → JSON lines, the same note shape the app reads from AnkiDroid |
| `local/` | Your data (gitignored): `jmdict.sqlite`, `deck.jsonl`, JMdict download |

## Run it

**Phone:** install the debug APK (built by CI on every push, or `./gradlew :app:assembleDebug` →
`app/build/outputs/apk/debug/app-debug.apk`).
1. *Choose AnkiDroid*: pick your parallel build (copy) or the real one, then grant access. Read-only either way.
2. *Run probe* → *Copy report*, and send it back. It settles which provider columns exist (PLAN.md §10).
3. *Dictionary → Download* (10 MB, built on the phone in about a minute).
4. *Your deck → Read from AnkiDroid*.
5. *Calibrate*: paste text or tap a sample → *Analyze*. Tap a word to mark it Known, a Name, or Ignore.
   Move the slider to see the unknown rate at each frequency threshold.

**Desktop (JVM 17+):**
```
./gradlew :cli:installDist
curl -o local/JMdict_e.gz https://www.edrdg.org/pub/Nihongo/JMdict_e.gz
cli/build/install/cli/bin/cli build-jmdict local/JMdict_e.gz local/jmdict.sqlite
python3 tools/export_deck.py your-export.apkg local/deck.jsonl      # pip install zstandard
# optional, fiction frequency ranks: see core/src/main/resources/freq/SOURCES.md
cli/build/install/cli/bin/cli deck-report --jmdict local/jmdict.sqlite --deck local/deck.jsonl
cli/build/install/cli/bin/cli analyze --jmdict local/jmdict.sqlite --deck local/deck.jsonl --threshold 6000 text.txt
./gradlew :core:test        # the real-deck tests run when local/ has jmdict.sqlite and deck.jsonl
```

## Credits

JMdict © EDRDG, CC BY-SA 4.0. Kuromoji (Apache 2.0) with IPADIC. Frequency ranks from the JPDB v2.2
frequency dictionary (jpdb.io, compiled by Kuuube; see `core/src/main/resources/freq/SOURCES.md`). Test texts: Aozora Bunko (public domain).
