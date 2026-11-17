#!/usr/bin/env bash
# Builds the JMdict SQLite the app ships prebuilt (app/src/main/assets/jmdict.sqlite.gz, gitignored).
# Without it the app downloads JMdict and builds the database on the phone instead.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p local app/src/main/assets
[ -s local/JMdict_e.gz ] || curl -L -o local/JMdict_e.gz https://www.edrdg.org/pub/Nihongo/JMdict_e.gz
./gradlew -q :cli:installDist
out=local/jmdict-asset.sqlite
rm -f "$out"
cli/build/install/cli/bin/cli build-jmdict local/JMdict_e.gz "$out"
python3 -c "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute('VACUUM'); c.close()" "$out"
gzip -9 -c "$out" > app/src/main/assets/jmdict.sqlite.gz
rm -f "$out"
ls -la app/src/main/assets/jmdict.sqlite.gz
