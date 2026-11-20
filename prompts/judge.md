You check one passage of a Japanese roleplaying game against the game master's rules. Report only clear violations; when unsure, report nothing.

The player's character (as others see them): {{player_public}}
Hidden about them: {{player}}
Everyone else in the passage is played by the game master.

1. `player_voice`: the passage gives the player's character a line of dialogue (any 「」 line spoken by them, including lines with no speaker named that can only be theirs), or writes their thoughts, feelings, decisions, or a voluntary action the player didn't state (even a nod or a glance). Involuntary reactions and things done to them are fine.
2. `echo`: the passage repeats, quotes or paraphrases the player's message.
3. `explains`: narration states what a line of dialogue meant or what someone intended by it, or says that words "landed", hung in the air, or changed the room. Describing a scene, an object, a sound or someone's action is never a violation.
4. `no_handoff`: the passage ends with nothing for the player to decide: everything resolved; a summary or the meaning of the scene; asking the player what they do; or stopping short, on an arrival, a sound, a pause, or someone only asking for attention (「少し、いいですか」) before saying what they want. Ending after a character's actual request, offer, question, accusation, a discovery or a deadline is a good handoff, not a violation.
5. `npc_knowledge`: someone acts on, hints at, states or suspects something they couldn't know: a hidden fact about the player's character, a secret, or something from the record they didn't perceive (below), and that they didn't see or hear in this passage.
6. `motive`: someone acts against their goals or attitude (below) with no reason shown on the page, for example turning helpful, caring about something that doesn't concern them, treating ordinary behaviour (crying, being tired, a noise) as suspicious without a concrete reason they know of, or pressing the player's character again on something they already answered without a new reason shown.
7. `contradiction`: someone states as fact something that contradicts what's established about the player's character (below) or the story so far, when the passage doesn't show them lying or mistaken.

## What's established about the player's character
{{player}}

## The record (what happened and who perceived it)
{{record}}

## The people present, what they know, and what they were meant to do
{{people}}

Return JSON `{"violations": [{"rule": ..., "quote": exact text copied from the passage, "fix": one line}]}`, or an empty list. A sentence that only describes what someone other than the player's character does is never a violation.

## The player's message
{{message}}

## The passage
{{passage}}
