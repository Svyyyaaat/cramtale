# Cramtale: plan

A Japanese roleplay story that is also your Anki review session. This plan comes
before any code. Section 11 lists the decisions I need from you.

---

## 1. What I took from your two apps

**Cram Dungeon (`Anki-Dungeon-Remixed`)** talks to AnkiDroid through its raw
content provider (`content://com.ichi2.anki.flashcards`, `AnkiDroidBridge.java`):

- It reads the queue with `query(schedule, "limit=?,deckID=?")`. That is AnkiDroid's own
  scheduler, so it already applies the deck's new-card limit, review limit and learning steps.
  The queue is rebuilt on every query and spreads new cards through it, so the game
  works through a long batch instead of taking only the head.
- It reads card state (`reps, type, queue, due`) from
  `notes/<id>/cards/<ord>`, falling back to fewer columns on older AnkiDroid.
- It answers with `update(schedule, {note_id, ord, answer_ease, time_taken})`. That is
  a real Anki answer, not a direct edit of the due date.
- Every write is **checked by reading the card back** (AnkiDroid reports success even
  when it swallowed an error). Writes it didn't record are retried. A **journal of sent
  answers** finds answers that AnkiDroid later lost (a rollback or a full sync) and sends
  them again. Bury and suspend wait until the card is graded, because the API can't undo them.
- It finds new cards with Anki search strings through `query(notes, search)`
  (`deck:"…" is:new -is:suspended prop:pos<=N`).
- Code layout: a pure-Java `core` module with JVM unit tests, and an `android` module
  for the platform. CI runs `:core:test` and then builds the APK.

**Auto Anki Miner (`Auto_Anki_Miner`)** creates the cards:

- It is Kotlin + Jetpack Compose (AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, minSdk 26) and
  uses `AddContentApi` from `com.github.ankidroid:Anki-Android:api-v1.1.0`.
- Note type **`Japanese Core 5k`**, with fields `Lemma, Romanization, Part of Speech,
  English Gloss, Sentence, Sentence Pronunciation, Sentence Translation, Sentence Audio`.
  Furigana uses Anki bracket notation with a space before each run (` 飛[と]び 込[こ]む`).
  Tag `auto-anki-miner`. Duplicates are checked on the first field. The deck is created
  if it is missing.
- The work is split between code and the model. Code does everything exact: tokenizer base
  form, JMdict lemma and reading candidates, POS codes, Hepburn, furigana layout,
  consistency warnings. Claude makes only the judgment calls (which reading or sense, the
  gloss, the translation), returned through a JSON schema.
- Tokenizer: **Kuromoji IPADIC** in the app (`Text.kt`), Janome in Termux, plus JMdict as SQLite.
- It has labelled test sets of about 510 words (`tools/cases*.json`) for readings and lemmas.

**What I reuse:** Cram Dungeon's raw-provider approach with its verify, retry and journal
logic, ported to Kotlin. I use it for adding notes as well, not `AddContentApi`.
`AddContentApi` hard-codes the AnkiDroid authority, which would stop me pointing the app
at a copy of your collection (section 4.6). From the Miner I reuse the Kotlin/Compose stack,
Kuromoji, the Core 5k field mapping, and the code-does-the-exact-parts split. I port
`romanize`, `align`, `format_furigana`, `pos_codes` and the JMdict lookup from Python to
Kotlin and test them against your `cases*.json`.

## 2. Platform and stack

- Android app, Kotlin, Jetpack Compose, Gradle KTS, with the same versions as the Miner.
- Modules, split the same way as Cram Dungeon:
  - `core/`: pure Kotlin/JVM. Matcher, grading engine, story engine, state files,
    prompt assembly, Claude client, logs. All unit-tested on the JVM, including in this
    cloud container, which has no Android SDK.
  - `app/`: Android. AnkiDroid gateway, Compose UI, Keystore, file storage.
- Claude calls go through the **official Anthropic Java SDK** (`com.anthropic:anthropic-java`),
  which works from Kotlin. If it doesn't run on Android (it pulls in Jackson and OkHttp),
  I fall back to plain OkHttp against `/v1/messages`. I'll check that first in step 2.
- CI: a GitHub Actions workflow like your two repos. It runs `:core:test` and builds the debug APK.

## 3. Architecture

```
          ┌────────────── app (Android) ───────────────┐
          │  Compose UI: story · tap popup · settings  │
          │  AnkiGateway (raw provider, verify/retry)  │
          └───────┬───────────────────────┬────────────┘
                  │                       │
 core ────────────▼───────────────────────▼──────────────────────────
  DueSnapshot ← AnkiGateway            Matcher (Kuromoji + JMdict + deck index)
  GradingEngine (rules §4)  ←──────── found-in-text, taps
  StoryEngine: Director · Writer · Checks · Dice · Complications · Scribe
  StateStore (markdown/JSON files)   PromptStore (prompts/, config/)
  ClaudeClient (cache, cost log)     WriteLog (every Anki write, JSONL)
```

## 4. Anki layer

### 4.1 Which words are "my deck"
- Settings pick the **review decks** (default: your Core 5k deck and `RP`), the
  **note types and the field holding the word** (default `Japanese Core 5k` / `Lemma`),
  and **the recognition template** (default: the template named like "Recognition", otherwise
  ord 0). Only recognition cards are ever graded. Other cards of the same note are ignored.
- The deck index is built from note fields (`query(notes, 'note:"Japanese Core 5k"')`).
  Each lemma has HTML and furigana stripped and is normalised to a JMdict entry, so
  spelling variants of the same entry share one key (section 5.2).

### 4.2 "Due right now"
Before **every** passage the app takes a fresh `DueSnapshot`:
1. It queries the scheduler queue for each review deck with a large limit. That is Anki's
   own view of today, with new-card and review limits already applied.
2. It drops non-recognition cards.
3. It reads each card's state. **Learning cards** (queue 1/3) count only if `due ≤ now`, so
   a step delay must really have passed. I ignore Anki's "learn ahead" window, which would
   otherwise show them up to 20 minutes early. **Review cards** count if they are in the
   queue (due today). **New cards** count if Anki's queue offers them, so Anki's new limit
   decides when they appear.

The writer gets this pool. A card can be graded only for a passage whose snapshot
contained it. Just before sending, its state is read again: if it changed (for example
you reviewed it in AnkiDroid meanwhile) or it is no longer due, nothing is sent and the
log entry says why.

### 4.3 Grading rules (one graded read = one answer)
Grades are committed when you send your reply (or tap **Continue**) on a passage. Each
card is graded at most once per passage.

| Situation in the committed passage | Sent |
|---|---|
| Due review/learning card, in the text, not tapped | Good (3) |
| Due review/learning card, tapped (any version of this passage) | Again (1) |
| New card offered by Anki, first appearance (meaning shown inline under the word) | Again (1), which starts its learning steps |
| New-then-learning card later, due again, untapped | Good. Repeated at each due moment until Anki graduates it |
| Card in deck, in the text, **not** due | nothing (free exposure) |
| Card in deck, tapped, **not** due | **see question Q1**: by default an Again is held and sent at the card's next due moment |
| Optional (off by default): review card with interval < 21 days | needs two untapped reads in separate passages. The first is recorded and sends nothing. The second sends Good. A tap at any point sends Again |

- The answer time sent is the passage's reading time split across its graded cards,
  clamped to 1–60 s. Anki uses it only for statistics.
- Rerolled or edited passages: only the passage you commit is graded. Taps made on any
  discarded version of the same turn carry over, because you saw those meanings.
- What happens next (intervals, steps, relearning) is entirely Anki's. The app keeps no
  schedule, no limits and no counters of its own beyond the optional two-read note and the
  safety cap below.

### 4.4 Safety modes
| Mode | May do | Default |
|---|---|---|
| **Read-only** | read decks, notes, queue, card state | ✔ |
| **Add-only** | + create notes in the RP deck (new words, approved mistake cards) | |
| **Grading** | + send answers, up to a **daily cap** (default 150). Past the cap the app behaves as read-only | |

- The mode is a single setting, checked in the gateway itself. A write call in the wrong
  mode throws, so no UI bug can bypass it.
- The app never sets due dates, intervals, ease or "forget", and never buries or suspends.
  It only sends answers and adds notes.

### 4.5 Logs, the dry-run report, and undo
- **WriteLog** (`writes.jsonl`) records every write, and in read-only mode every write it
  *would* have sent. Each entry holds the time, mode, kind (`answer`/`addNote`), card or note
  ids, ease, the card state before and after, the passage id and the sentence.
- **Dry-run report** (read-only mode): for each would-be grade, the app later looks at what
  you really did in AnkiDroid. It works this out from the card state (reviews went up and
  lapses went up means you pressed Again). The report shows agreement, plus the two costly
  disagreements: the app says Good where you said Again (a leak or an over-easy context), and
  the reverse. For an exact version, `tools/dryrun_report.py` reads the revlog from an
  exported **copy** of your collection plus the app's log.
- **Undo**:
  - **Notes** the app added are tagged `cramtale` and can be deleted from the log screen
    through the provider's note delete. I'll confirm in step 1 that AnkiDroid supports it.
    If it doesn't, the screen lists them for deletion in the AnkiDroid browser
    (`tag:cramtale`).
  - **Answers cannot be undone through AnkiDroid's API.** The log keeps exactly what was sent
    and the state before, so they can be reverted by hand, and grading mode stays behind the
    cap and an explicit switch. I won't fake an undo by editing due dates, because that would
    break your "never change intervals directly" rule.

### 4.6 A copy of your collection, never the real one
- The provider authority is a setting. During development the app targets an **AnkiDroid
  parallel build** (AnkiDroid publishes side-by-side installs with their own package). You
  import a backup of your collection into it, and the real AnkiDroid is never touched.
  In step 1 I'll verify that the parallel build exposes its own provider and permission.
- Switching to the real `com.ichi2.anki` needs a typed confirmation in the app, and **I will
  ask you before any step that does it**.
- In this container I test against a `FakeAnkiGateway` that implements the scheduler rules
  (learning steps, new limit, due times), plus the optional Python harness that runs the real
  Anki scheduler (`pip install anki`) on a copy of a collection, if you send one.

## 5. Matcher and checker

### 5.1 Tokenizer
**Kuromoji IPADIC.** It already runs in your Miner on Android, is small and is fast. Sudachi
normalises better, but its dictionary is 70–200 MB. I get most of that normalisation from
JMdict instead (5.2). Step 1 measures lemma errors on real text. If they are high, I try
`kuromoji-unidic` before Sudachi.

Ignored parts of speech: interjections (感動詞), particles (助詞), auxiliaries (助動詞), symbols
(記号), numbers (数), proper nouns (固有名詞), and whitelisted character names. Bound
suffixes and prefixes are attached to their word.

### 5.2 Normalisation and matching
- JMdict (downloaded once on first run, about 60 MB compressed, streamed into SQLite like the
  Miner's `build_jmdict.py`) maps every kanji and kana spelling to an **entry id**. The match
  key is that entry id, with the base form as fallback. That covers 何故/なぜ, 付属/附属,
  かつ丼/カツ丼, and okurigana variants.
- **Multi-word deck entries** (腹が立つ, 気がする, 仕方ない) are matched over token sequences on
  base forms, **longest first**, before single words.
- **Known** = deck ∪ frequency list ∪ words you marked known ∪ names.
- **Frequency list:** built in from JMdict's priority tags (`nf01–nf48` ranks, `ichi1`, `news1`,
  `spec1`), so no extra licence is needed. Step 1 calibrates the threshold on your marks.
  Importing a ranked list (one word per line) is optional.

### 5.3 Checks on every passage, before you see it
1. **Target words:** the writer returns `{lemma, form}` for each due word it used. Code
   confirms the exact form is in the text and the matcher agrees on the lemma. Words it
   claims but didn't use are dropped from grading.
2. **Unknown rate:** unknown content tokens / content tokens must be < 3% (a setting). One
   rewrite naming the offending words, then accept.
3. **Banned phrases:** `config/banned.txt` holds regexes. A hit rewrites that sentence once.
4. **Leak check:** Haiku sees the passage with each due word blanked (□) and guesses the word.
   A correct guess means that sentence rewrites once.
5. **Rule judge:** Haiku checks for writing your character's words, actions or feelings,
   repeating your message, explaining a line of dialogue, and not stopping at a decision. One
   rewrite, then accept and log.
- **Due-word pool:** the writer gets the pool and uses what fits. Words that were offered but
  skipped gain priority next passage (code-side counter).

## 6. Story engine

### 6.1 Calls (each its own job)
| Call | Model | When |
|---|---|---|
| Director: plans 3 beats for the session from state | `claude-opus-5-5`, effort `low` | session start |
| Writer: one passage | `claude-opus-5-5`, effort `low` (the lowest; thinking can't be turned off on this model) | each turn |
| Adjudicator: is your action uncertain? stakes and difficulty | `claude-haiku-5-5` | after your message |
| Mistakes: your Japanese errors as JSON (section 8) | `claude-haiku-5-5` | after your message |
| Checks: leak guess, rule judge, in-sentence meaning for the popup | `claude-haiku-5-5` | per passage / per tap |
| Scribe: proposes state changes as JSON ops; code validates and applies them | `claude-haiku-5-5` | after each passage |
| Off-screen: advances clocks and agendas between sessions | `claude-opus-5-5`, `low` | session start |
| Chapter summary | `claude-opus-5-5`, `low` | every ~30 passages or at a token threshold |

API notes from the current docs: Opus 5.5 rejects `temperature`/`top_p`, assistant prefill and
disabled thinking, so effort is the only lever. Structured JSON uses `output_config.format`.
The writer streams. Server-side refusal fallback (`fallbacks: "default"`) is on for Opus calls,
so a rare refusal doesn't kill a turn. It can be turned off.

### 6.2 Code owns the rules
- **Dice:** when the adjudicator says an action is uncertain, code rolls 2d6 + modifier from the
  character sheet. 10+ is success, 7–9 success at a cost, 6 or less failure. The writer gets the
  *result*, never the roll to decide. A **failure writes a consequence** into state (a door
  closed, trust lost, an item gone), and the writer is told it stands.
- **Complications:** `config/complications.md` is a grounded table (weather, a tool breaks, a
  witness, a bill comes due…) tagged by place. Code draws one per scene and doesn't repeat until
  the table is used up.
- **State files** (plain text, editable in-app, versioned per session):
  - `characters/<name>.md`: goals, limits, secrets, what they want from you, attitude, where they are
  - `world.md`: places, factions, and off-screen **clocks** (e.g. "the landlord sells, 3/6")
  - `secrets.md`: GM secrets and **who knows what**. The scribe can't reveal a secret to someone
    who wasn't in the scene.
  - `consequences.md`: pending consequences, debts, deadlines
  - `chronicle.md`: outcomes that still matter. Pruned when they stop mattering.
- **Injection:** each passage gets only the characters present (full file) and others (one line),
  the secrets relevant to them, consequences due now, chronicle lines tagged with this place or
  these people, the current beat, the dice result and the complication.
- Characters refuse, bargain, lie and act between scenes. That lives in their files and in the
  off-screen step, not just in the prompt.

### 6.3 Prompt layout and caching (fixed content first)
```
system  [cached]   GM frame · hard rules · config/plot.md · style sample · world bible
messages [cached up to the last breakpoint]  chapter summary · passages and replies so far
user               your reply
system (mid-conversation, volatile)  scene packet · dice result · complication · due-word pool ·
                   weak points · … · STYLE LINE LAST
```
Mid-conversation system messages keep the cached history intact. A new chapter resets the
history to its summary. That keeps the context short and stops habits from compounding.

### 6.4 Prompts and taste
- Every prompt is a plain file in `prompts/` (writer, director, adjudicator, mistakes, leak,
  judge, scribe, offscreen, summary, style_picker). They are editable in-app with a reset
  button. Output formats are fixed by code, so editing a prompt can't break parsing (the same
  pattern as the Miner).
- `config/plot.md` is seeded with your text verbatim.
- **Before writing the prompts** (step 2) I read the current SillyTavern docs and community
  presets for Claude roleplay, and adapt what applies: the GM framing, user-character agency,
  anti-echo, ending on a hook, and the slop lists for the banned file.
- **Style picker** (first run): the writer produces one short scene in four clearly different
  styles (for example: terse hard-boiled first person; close third literary; dialogue-driven;
  plain reportage). All four go through the unknown-word check so each is readable. You pick
  one. It is saved as `config/style_sample.md` and goes into the writer prompt, with the style
  line last.
- **Positive vs prohibition test:** `tools/style_ab` runs the same 10 scenes with both versions
  of the style rules and scores them on banned-regex hits, rule-judge violations and checker
  retries. I keep the winner and record the result in `docs/`. It costs API money, so I'll ask
  before running it.

### 6.5 Costs and key
- The API key is stored only on the phone, encrypted with an Android Keystore key.
- Each call's usage (input, cache write, cache read, output) is priced from
  `config/prices.json` and appended to `costs.jsonl`. Each session shows its total, split by call type.

## 7. UI (Compose)
- **Story screen:** passages as tappable tokens. Each content word is a clickable span. New cards
  show their meaning in small text under the word. Tapped words stay marked. Each passage has
  **Reroll** and **Edit** (edit the text; it is re-checked). Your messages show their corrections beside them.
- **Popup** (bottom sheet): reading, JMdict senses, a one-line meaning in this sentence (a Haiku
  call, cached), and **I know it** (makes no card). For words outside the deck it also says
  "will be added to RP" or "queued".
- **Settings:** mode, AnkiDroid target, decks, note types, recognition template, two-read
  option, caps, models and effort, thresholds, the key.
- **Logs:** writes, the dry-run report, costs, proposed cards awaiting approval.

## 8. New-word cards and mistake cards

**New word (add-only mode):** a tapped word not in the deck goes through spelling variants
(section 5.2). If it is an ordinary JMdict word (not a name or a particle), a `Japanese Core 5k`
note goes to deck `RP`:
- `Lemma`, `Romanization`, `Part of Speech`: from JMdict and the ported Miner code.
- `English Gloss`: the JMdict sense Haiku picks for this sentence.
- `Sentence`: the story sentence. `Sentence Pronunciation`: furigana by the Miner's method.
- `Sentence Translation`: Haiku.
- `Sentence Audio`: empty for now (see Q5).
- Tagged `cramtale`, with a duplicate check on `Lemma`.

At most N notes a day (default 10). The rest wait in a queue kept on the phone. Anki's new-card
limit decides when they reach the story.

**Your mistakes:** after each message, Haiku returns
`{errors:[{wrong, right, type, reason, key}]}`, for example key `熱い↔暑い`, definite errors only.
They show beside your message. The second time the same key comes up, the app proposes a card,
which you approve or reject. Your top recent keys go to the writer as "use these correctly where
natural".

**Proposed mistake-card format:** your Core 5k type doesn't fit (its front would show the answer).
I propose Anki's **stock `Cloze` note type**, which every collection has. That means no new note
type is created:
- `Text`: your corrected sentence with the spot blanked: `今日は本当に{{c1::暑い::hot (weather)}}ね。`
- `Back Extra`: `You wrote 熱い. 暑い is for weather and air, 熱い for things you touch.  key: 熱い↔暑い`
- Deck `RP`, tags `cramtale cramtale-mistake`.

Cloze cards are recall cards, so these are the one exception to "recognition only", and only
because you asked for them. If your collection has no `Cloze` type, or you'd rather have a
dedicated type, that needs a new note type, and I'll stop and ask (Q2).

## 9. Build order

Each step ends with a report: what works, what is untested, and how to run it.

1. **Matcher calibration**
   - `core`: Kuromoji tokenizer wrapper, JMdict builder and lookup, deck index, frequency tiers,
     the unknown-rate calculator, and the ported Miner helpers.
   - Tests on real Japanese text (public-domain Aozora excerpts plus modern dialogue I write) and
     on your `cases*.json`.
   - `app`: read-only **Anki probe** screen (provider columns, parallel build, queue sample,
     delete support) and the **calibration screen**: paste text, see flagged words with reasons,
     mark them known, a name or ignored, and see the unknown % at each frequency threshold.
   - Also a JVM CLI for the same, which runs here.
2. **Story loop, read-only:** the prompt research, prompts/ and config/, the Claude client with
   caching and cost log, director/writer/checks/dice/complications/scribe/state, the tap UI and
   popup, reroll and edit, chapters, the style picker, the dry-run log and report, and the style
   A/B harness (run only with your OK).
3. **Add-only:** RP notes with the daily cap and queue, mistake cards with approval, the write
   log, and note undo.
4. **Grading:** answers with verify, retry and journal (ported from Cram Dungeon), the daily cap,
   and the two-read option. First against the parallel-build copy. The real collection only after
   you say so.

## 10. Risks I already see
- **Provider columns:** `due`/`queue` exist on current AnkiDroid (Cram Dungeon uses them).
  `ivl`/`lapses` may not. The two-read option needs `ivl`; without it I'll derive the interval or
  disable the option. The step 1 probe settles this.
- **Queue semantics:** if the schedule query includes learn-ahead cards, my `due ≤ now` filter
  handles it. If it ever leaves out due learning cards, I'll also search `is:due` per deck.
- **Writer compliance:** Opus at low effort may skip due words or overshoot 3% unknown. Skipped
  words gain priority, there is one retry, and acceptance is logged so it can be tuned.
- **Cost:** Opus 5.5 is $4/$20 per M tokens ($0.20 for cache reads); Haiku 5.5 is $0.10/$0.50.
  With caching, a turn should be a few cents. The log will show the real number.
- **No Android SDK in this container:** I build the APK through the CI workflow (or install the
  SDK here if the network allows) and test `core` locally. On-device behaviour stays "untested"
  until you run it.

## 11. Decisions I need from you

- **Q1: tapping a deck word that isn't due.** You want this graded Again, but you also say a card
  is answered only when Anki says it is due. My default keeps both rules: hold the Again and send
  it the next time Anki says the card is due, even if the word doesn't appear in that passage.
  The alternative is to answer Again right away (an early answer).
- **Q2: mistake-card format.** Is stock `Cloze` (Text + Back Extra) acceptable, or do you want a
  dedicated note type? A dedicated type is a new note type, so I'll stop and design it with you.
- **Q3: decks and templates.** Which deck(s) hold your 4–5k words, and is the recognition card
  ord 0 of `Japanese Core 5k`? I can also just read this off the step 1 probe.
- **Q4: development copy.** Is an AnkiDroid parallel build with an imported backup the right
  "copy" for you?
- **Q5: audio on RP cards.** Empty for now? Later options are Android TTS to a file, or handing
  the note to your Miner's Termux edge-tts.
- **Q6: "I know it" on deck words.** I show it only for words outside the deck. A tap on a deck
  word stays a tap.
