#!/data/data/com.termux/files/usr/bin/bash
# Cramtale, one-time Termux setup: Claude Code for the story. Safe to re-run (also updates things).
set -u

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m!! %s\033[0m\n' "$*"; exit 1; }

BASE="$HOME/.cramtale"
mkdir -p "$BASE/work" "$BASE/calls"
[ -f "$BASE/claude_call.py" ] || die "claude_call.py is missing. Tap Install in Cramtale again."

say "Installing packages (python, nodejs, curl)"
# apt rather than pkg: pkg needs curl, which breaks when packages are half
# updated ("cannot locate symbol ... libcurl.so"). And a full upgrade, not just
# an index refresh, so new packages never land on old libraries.
APT_OPTS="-y -o Dpkg::Options::=--force-confold"
apt update || die "apt update failed (try: termux-change-repo)"
apt full-upgrade $APT_OPTS || die "apt full-upgrade failed"
apt install $APT_OPTS python nodejs-lts curl || die "apt install failed"

say "Letting the app run commands in Termux"
mkdir -p "$HOME/.termux"
PROPS="$HOME/.termux/termux.properties"
touch "$PROPS"
if grep -q '^[# ]*allow-external-apps' "$PROPS"; then
  sed -i 's/^[# ]*allow-external-apps.*/allow-external-apps = true/' "$PROPS"
else
  echo 'allow-external-apps = true' >> "$PROPS"
fi
termux-reload-settings 2>/dev/null || true

claude_works() { command -v claude >/dev/null && claude --version >/dev/null 2>&1; }

say "Installing Claude Code"
if grep -q proot-distro "$PREFIX/bin/claude" 2>/dev/null; then
  # Already set up via Debian on an earlier run; just update it there.
  proot-distro login debian -- /root/.local/bin/claude update || true
else
  npm install -g @anthropic-ai/claude-code@latest || true
fi

if ! claude_works; then
  say "Native Claude Code doesn't run on this Android, using a Debian container (proot-distro)"
  npm uninstall -g @anthropic-ai/claude-code >/dev/null 2>&1 || true
  apt install $APT_OPTS proot-distro || die "apt install proot-distro failed"
  proot-distro list 2>/dev/null | grep -q 'debian.*installed' || proot-distro install debian || true
  proot-distro login debian -- bash -c '
    set -e
    command -v curl >/dev/null || { apt-get update && apt-get install -y curl ca-certificates; }
    curl -fsSL https://claude.ai/install.sh | bash
  ' || die "Installing Claude Code inside Debian failed"
  cat > "$PREFIX/bin/claude" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Shim (Cramtale / Auto Anki Miner): runs Claude Code inside the proot-distro Debian.
exec proot-distro login debian --shared-tmp -- /root/.local/bin/claude "$@"
EOF
  chmod +x "$PREFIX/bin/claude"
  claude_works || die "Claude Code still doesn't start. Copy the output above into an issue."
fi

say "Done: $(claude --version)"
cat <<'EOF2'

Last steps:
  1. Run  claude  here once and log in with your Claude subscription
     (then type /exit). Your login stays inside Termux.
  2. Go back to Cramtale and tap "Test".
EOF2
