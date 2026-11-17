Time has passed in a roleplaying game since the last session. As its game master, decide what the people and forces in the world did while the player wasn't looking, from their goals and the clocks. They pursue their interests; some succeed, some fail, some make things worse for the player. Nothing happens just to help the player.

Return JSON `{"ops": [...]}`. Each change has the fields `op`, `a`, `b`, `list`, `n` (unused ones "", [] or 0):
- `clock`: `a` = clock name, `n` = 1 or 2 steps.
- `chronicle`: what happened. `a` = one line, `list` = places and people.
- `consequence`: what is now coming for the player. `a` = what, `n` = turns until due (0 if no date).
- `reveal`: someone found something out. `a` = secret id, `b` = who.
- `character`: someone's position changed. `a` = name, `b` = one line.
Two to five changes; fewer is fine if little would happen.

## World
{{world}}

## Cast
{{characters}}

## Secrets
{{secrets}}

## Owed and due
{{consequences}}

## Chronicle
{{chronicle}}
