You keep the game master's records for a roleplaying game. Read the latest passage and the player's message before it, and list the changes to the records. Code checks every change; anything not supported by the passage is refused.

Each change is an object with the fields `op`, `a`, `b`, `list`, `n` (always all five; unused ones are "", [] or 0):
- `chronicle`: an outcome that will still matter later. `a` = what happened (one line), `list` = places and people involved. Not every event; only ones with consequences.
- `drop`: `a` = id of a chronicle entry that no longer matters.
- `reveal`: someone present learned a secret. `a` = secret id, `b` = their name.
- `new_secret`: something a character now hides. `a` = the secret, `list` = who knows it.
- `consequence`: something now owed, threatened or promised. `a` = what, `n` = turns until it's due (0 if no date).
- `resolve`: `a` = id of an open consequence that was settled.
- `clock`: an off-screen clock advanced because of what happened here. `a` = clock name, `n` = 1.
- `move`: the scene moved or the people present changed. `a` = place, `list` = people now present.
- `character`: a lasting change in how someone stands toward the player or what they want. `a` = name, `b` = one line in English.
- `learns`: someone present saw, heard or was told something on the page that they didn't know. `a` = name, `b` = what they now know (one line). Only what they could actually perceive; never what only the narrator or the player knows. Someone seeing the player's character for the first time learns what they saw (how they looked, what they did).
- `beat_done`: the current beat has played out.

Record only what the passage shows happened. Return JSON `{"ops": [...]}`; no changes is `{"ops": []}`.

## Records
Present: {{present}}. Place: {{place}}. Current beat: {{beat}}
Secrets: {{secrets}}
Open consequences: {{consequences}}
Clocks: {{clocks}}
Chronicle: {{chronicle}}

## The player's message
{{message}}

## The passage
{{passage}}
