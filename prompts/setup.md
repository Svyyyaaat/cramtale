Create the starting material for a Japanese roleplaying game, as its game master. The player's taste in stories:

{{plot}}

{{seed}}

Make a small, grounded world: one town or neighbourhood, present day unless the seed says otherwise, adults with work, money and history. The player's character is someone with a stake in it and something to lose (describe them only by situation, never by personality or feelings: the player plays them).

Return JSON:
- `title`: a short title for the story (2–6 words, English), the kind on a book spine.
- `world`: markdown, under 250 words: the place, the institutions and money that matter, what is going wrong.
- `player`: two sentences: who the player character really is, what they have, what they owe, including anything hidden.
- `history`: eight to twelve things that happened before the story starts, the record everyone's memory is built from. Each is `text` (one concrete line in English: what happened, when, how long, to whom) and `witnesses` (the names of who saw it, took part or was told it; "PLAYER" for the player's character). Cover the player's character's recent past, how they know each person, and what each person saw or was told about them. Anything not in here, a person doesn't know.
- `player_public`: one or two sentences: only what other people can see or have heard about the player character (looks, role, reputation). Anything hidden stays out.
- `characters`: the two or three people the story turns on, each `name` (Japanese), `file` (markdown, under 170 words, with sections Goals, Busy with, Limits, Secrets, Knows, Attitude). Each person has a life that doesn't revolve around the player: `Busy with` is their own errands, work and worries right now. `Knows` lists what they actually know or believe, written from inside their head (usually only the public side of the player character). Never write what someone doesn't know or can't explain ("doesn't know what he is"): that's the game master's knowledge, and it tells whoever plays them there is something to know. Each person wants something that conflicts with someone else. Anyone whose goal is to find something out (a guard, an inspector, a worried parent) gets two or three leads in `Knows` that point at different people or places, so the player's character is at most one lead among several. Every lead names a concrete person or place with a concrete detail ("the charcoal burner Henrik has started working nights"), never a vague group ("several woodcutters") next to the one named person who is the player's character. If someone has noticed something odd about the player's character, it sits among other things on their mind, and they have an ordinary explanation for it too. Give them different attitudes (some warm, some guarded, some indifferent): how readily they trust or agree is part of who they are, not a rule for everyone.
- `extras`: two to four other people the story will need around these (a gate guard, a neighbour, a rival trader), each `name` (Japanese) and `line` (one sentence in English: who they are, what they want right now, their manner). No secrets, no leads, no ties to the plot beyond their job; they're the town, not the mystery.
- `secrets`: `text` and `knows` (names).
- `clocks`: two or three off-screen threats, `name` (Japanese), `size` 4–8, `when_full`.
- `complications`: ten grounded complications for this world, each one line, optionally starting with a place tag in brackets.
- `ambience`: fifteen small background happenings in this world's places (sounds, weather, animals, people passing, work going on nearby), each one line in English, optionally starting with brackets holding place tags and options separated by ";": "sticky N" for something that goes on a few turns (weather, a festival, work next door), "group G" for things that exclude each other (weather). Example: "[market; sticky 3] A bell-ringer calls out the hour, again and again." Ordinary things, nothing that drives the plot.
- `opening`: `place`, `present` (names), and one sentence on the situation the first passage starts in.
