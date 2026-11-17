# Testing this build on the phone

Still read-only: nothing is written to AnkiDroid. What to try, and what to look for.

## Turns that survive
- Open the story, read the passage, tap a couple of words. Swipe the app away and reopen it: the same
  passage comes back with the same words highlighted, and no new passage is written (the cost in the corner
  doesn't move).
- Turn on airplane mode, type a reply and send it. You should see "Can't reach Claude…", your reply still in
  the box, and *Try again*. Turn the network back on and tap it: the passage is written for that same reply.
- *Reroll* twice: ‹ 3 / 3 › appears; the arrows switch versions; this survives a restart too.

## Anki
- Add a card with the miner, wait until it's due (or study it once in AnkiDroid so it's in learning), then
  continue the story: the word can now show up (the story reads new notes from AnkiDroid by id).
- *AnkiDroid connection → Run the probe*: the new line `nid: search … (works)` should say works. If it says
  MISMATCH or FAILED, send me the probe text.
- Home → *Today*: the due number should match AnkiDroid's deck list (sum of your vocabulary decks).

## Reading
- Tap 榊-type names: reading and who it is, no "I know it". Story characters aren't underlined as unknown.
- Tap a Core 5k word: *Your card* shows the card's English Gloss.
- *Listen* and the speaker in the word popup. If the phone has no Japanese voice, a note says how to add one.
- Settings → Text size: the passage changes size at once.

## Settings
- Passage length *Long*: longer passages with more review words (aim about 12). *Short*: about 4.
- *Writing style → Choose again*, then Back: your old style comes back. Pick one instead: it's used from the
  next passage.
- *Start a new story*: the old one is archived on the phone, you go straight to "Your story"; your style,
  taste and mistakes carry over.

## If something breaks
The error box now starts with what to do and ends with the technical chain; a screenshot of it is enough.
