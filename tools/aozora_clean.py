#!/usr/bin/env python3
"""Aozora Bunko text (UTF-8) -> plain text: no header/footer, ruby, ｜ markers or ［＃…］ notes."""
import re
import sys

src = open(sys.argv[1], encoding="utf-8").read()
parts = re.split(r"^-{20,}\s*$", src, flags=re.M)
body = parts[2] if len(parts) >= 3 else src
body = body.split("底本：")[0]
body = re.sub(r"《[^》]*》", "", body)
body = re.sub(r"［＃[^］]*］", "", body)
body = body.replace("｜", "")
body = re.sub(r"\n{3,}", "\n\n", body).strip() + "\n"
open(sys.argv[2], "w", encoding="utf-8").write(body)
