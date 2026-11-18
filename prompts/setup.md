Create the starting material for a Japanese roleplaying game, as its game master. The player's taste in stories:

{{plot}}

{{seed}}

Make a small, grounded world: one town or neighbourhood, present day unless the seed says otherwise, adults with work, money and history. The player's character is someone with a stake in it and something to lose (describe them only by situation, never by personality or feelings: the player plays them).

Return JSON:
- `world`: markdown, under 250 words: the place, the institutions and money that matter, what is going wrong.
- `player`: two sentences: who the player character really is, what they have, what they owe, including anything hidden.
- `player_knows`: markdown, under 150 words: what the player's character knows that the player needs in order to play them: their recent past (what they did, where they were, for how long), their relationships with each character, what people around them know or say about them. Concrete facts the story can rely on; nothing about personality or feelings.
- `player_public`: one or two sentences: only what other people can see or have heard about the player character (looks, role, reputation). Anything hidden stays out.
- `characters`: three or four people, each `name` (Japanese), `file` (markdown, under 170 words, with sections Goals, Busy with, Limits, Secrets, Knows, Attitude). Each person has a life that doesn't revolve around the player: `Busy with` is their own errands, work and worries right now. `Knows` lists what they actually know, including what they know and don't know about the player character (usually only the public side). Each person wants something that conflicts with someone else, is slow to agree and hard to impress.
- `secrets`: `text` and `knows` (names).
- `clocks`: two or three off-screen threats, `name` (Japanese), `size` 4–8, `when_full`.
- `complications`: ten grounded complications for this world, each one line, optionally starting with a place tag in brackets.
- `ambience`: fifteen small background happenings in this world's places (sounds, weather, animals, people passing, work going on nearby), each one line in English, optionally starting with a place tag in brackets. Ordinary things, nothing that drives the plot.
- `opening`: `place`, `present` (names), and one sentence on the situation the first passage starts in.
