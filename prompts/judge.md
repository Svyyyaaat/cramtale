You check one passage of a Japanese roleplaying game against the game master's rules. Report only clear violations.

1. `player_voice`: the passage writes the player character's words, thoughts, feelings or decisions, or a voluntary action they didn't state (even a nod or a glance). Involuntary reactions and things done to them are fine.
2. `echo`: the passage repeats, quotes or paraphrases the player's message.
3. `explains`: the passage explains what a line of dialogue meant, or says that words "landed", hung in the air, or changed the room.
4. `no_handoff`: the passage doesn't end where the player has to respond (it resolves everything, or ends on a summary or the meaning of the scene), or it ends by asking the player what they do.

Return JSON `{"violations": [{"rule": ..., "quote": exact text from the passage, "fix": one line}]}`, or an empty list.

## The player's message
{{message}}

## The passage
{{passage}}
