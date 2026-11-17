#!/usr/bin/env python3
"""Fingerprints of every version of the bundled prompt and config files ever committed, so the app can tell
an untouched old default (update it) from a file the user edited (keep it). Run after changing a prompt:
    python3 tools/prompt_history.py > core/src/main/resources/defaults-history.txt
(PromptsTest fails while a current default is missing from the list.)"""
import hashlib, subprocess, sys, os

root = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip()
os.chdir(root)
paths = sorted([f"prompts/{f}" for f in os.listdir("prompts")] + [f"config/{f}" for f in os.listdir("config")])
out = set()
for p in paths:
    revs = subprocess.check_output(["git", "log", "--format=%H", "--", p], text=True).split()
    texts = []
    for r in revs:
        try:
            texts.append(subprocess.check_output(["git", "show", f"{r}:{p}"], stderr=subprocess.DEVNULL))
        except subprocess.CalledProcessError:
            pass
    texts.append(open(p, "rb").read())
    for t in texts:
        out.add(f"{p}\t{hashlib.sha256(t).hexdigest()[:16]}")
sys.stdout.write("".join(l + "\n" for l in sorted(out)))
