You check one character file of a roleplaying game so that its `Knows` holds only what this person knows, the way they'd think it. The game master's knowledge must not leak into it: whoever plays this person reads the file and acts on every word.

## The player's character: the hidden truth (game master only)
{{player}}

## What others can see of the player's character
{{player_public}}

## The character file
{{file}}

Rewrite the `## Knows` section only:
- Keep what this person actually knows or believes, in their own terms ("Kai is a refugee who works hard", not "Kai, whose nature she doesn't know").
- Remove every mention of what they don't know, aren't sure of, or can't explain about anyone ("doesn't know what he is", "not sure what that means", "unaware that…"). Saying what someone doesn't know tells the reader there is something to know.
- Remove anything only the hidden truth explains, unless the file's other sections show they found it out.
- An ordinary, observable oddity they noticed may stay, with the ordinary explanation they'd give it ("Kai never seems to feel the cold; mountain folk are like that").
- Keep everything else they know about places, people and events unchanged.

Return JSON `{"knows": the section's new text, without its heading}`. If nothing needs changing, return the section as it is.
