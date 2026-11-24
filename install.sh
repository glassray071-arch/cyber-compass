#!/data/data/com.termux/files/usr/bin/bash
# CYBER//COMPASS installer for Termux
set -e
echo "▓▒░ installing CYBER//COMPASS ░▒▓"
pkg install -y python termux-api >/dev/null
DIR="$(cd "$(dirname "$0")" && pwd)"
install -m 755 "$DIR/cyber_compass.py" "$PREFIX/bin/cyber-compass"
echo
echo "done. run:  cyber-compass        (or: cyber-compass --demo)"
echo "NOTE: the Termux:API *app* must also be installed, from the same"
echo "      place you got Termux (F-Droid or GitHub), or sensors won't work."
