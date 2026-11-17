# Prompt research (Nov 2026)

What the story prompts are built on. Sources read: Pura's Director Preset 16.0 (tested on Opus 4.6 / 5),
NemoEngine v12.3, Marinara's Spaghetti Recipe v9.6, SillyTavern docs (Author's Note), Anthropic's prompting
docs for Opus 5 / 5.5 and mid-conversation system messages. Older material (Pixijb, Anthropic's "keep Claude
in character" page with prefill) was skipped: prefill now returns 400.

## What we adopted

| Problem | Technique | From |
|---|---|---|
| Writing the player's character | Spell out the boundary: the player's message is their character's attempt and words; the world, other people, costs and outcomes are yours. You may narrate what happens *to* them physically and what they perceive, never their words, thoughts, feelings or next choice. When it's unclear, leave it unwritten. Move the scene through NPC action and the world instead. | NemoEngine |
| Where a passage ends | End at a handoff: an action, consequence, discovery or tension the player must answer. No "What do you do?". A live opening can be a refusal, evidence, a changed risk, not only a question. | Pura, Marinara, NemoEngine |
| Echoing the player | Respond to what they said with a reaction or action, never by restating their words. Continue from the impact, not a replay. Placed last, with an example. | Marinara, NemoEngine |
| Explaining dialogue / announcing impact | Dialogue carries its own meaning. Show weight by consequence (a decision changes, a task stops), not by words "landing" or rooms freezing. | Pura, NemoEngine |
| Positivity, therapy voice | Agreement is begrudging, transactional, hard-won. No unseeded rescues or convenient hesitations. | Pura, NemoEngine |
| NPC agency | People act by their interests: initiate, refuse, bargain, lie (also by omission), withdraw; nobody is a prize or a tireless obstacle; they don't know others' thoughts; off-screen threads come back as evidence or consequence. | Pura, Marinara, NemoEngine |
| Proportion | Zero or one precise reaction beat; compress routine, linger only where it matters; no melodrama. | NemoEngine, Pura |
| Dice | The result is decided before the model writes; narrate it, don't pick the convenient outcome. | NemoEngine |
| Structure | Long fixed material first (cached), the request and the style rules last; per-turn context as a mid-conversation system message phrased as facts; history append-only. | Anthropic, SillyTavern Author's Note, Marinara |
| Style rules | Name each banned pattern and pair it with what to do instead; no "CRITICAL / MUST" shouting (over-triggers on Opus 4.5+). | Anthropic, Pura |
| Length | Effort doesn't control length on Opus 5.x: say the length in the prompt, near the end. | Anthropic |
| Reasoning | Never ask for visible reasoning in the reply (can trigger a reasoning_extraction refusal on Opus 5.5). | Anthropic |

## Positive statements vs prohibitions

Evidence is mixed: Anthropic recommends saying what to do, but also notes the model responds well to named
patterns to avoid; Pura moved to "never" wording for models that treat bans as suggestions. Cramtale keeps
both versions of the style rules (`config/style_rules_positive.md`, `config/style_rules_firm.md`) and the
A/B harness decides (PLAN.md §6.4). Until then the default is the combined form: a named pattern plus what to
write instead.

## Japanese

No published Japanese slop list was found for Claude. `config/banned.txt` is a starter list written for this
project (stock bodily reactions, "not X but Y", announced impact), to be grown from what actually shows up.
