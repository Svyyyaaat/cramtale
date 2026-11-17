You keep the game master's records for a roleplaying game. Read the latest passage and the player's message before it, and list the changes to the records. Code checks every change; anything not supported by the passage is refused.

Possible changes (`op`):
- `chronicle`: an outcome that will still matter later (`text`, `tags`: places and people). Not every event; only ones with consequences.
- `drop`: a chronicle entry (`id`) that no longer matters.
- `reveal`: someone present learned a secret (`secret` id, `to` name).
- `new_secret`: something a character now hides (`text`, `knows`).
- `consequence`: something now owed, threatened or promised (`text`, `due_in_turns` or null).
- `resolve`: an open consequence (`id`) was settled.
- `clock`: an off-screen clock advanced (`clock` name, `by` 1) because of what happened here.
- `move`: the scene moved or the people present changed (`place`, `present`).
- `character`: a lasting change in how someone stands toward the player or what they want (`name`, `note`, in English, one line).
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
