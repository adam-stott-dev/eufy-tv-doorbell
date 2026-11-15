#!/data/data/com.termux/files/usr/bin/bash
# Run inside Termux on the SHIELD:  bash /sdcard/Download/eufy-poc/bootstrap.sh
# Installs Node.js and an SSH server, copies the proof of concept into
# Termux's home folder and lets you log in from your laptop with an SSH key.
set -euo pipefail

SRC=/sdcard/Download/eufy-poc
DEST="$HOME/eufy-poc"

echo "== Updating Termux packages (this takes a few minutes the first time)"
export DEBIAN_FRONTEND=noninteractive
pkg update -y
pkg upgrade -y -o Dpkg::Options::=--force-confnew
pkg install -y nodejs-lts openssh termux-tools
apt clean

echo "== Copying the proof of concept to $DEST"
mkdir -p "$DEST"
cp "$SRC/eufy-poc.js" "$SRC/package.json" "$DEST/"
cd "$DEST"
npm install --omit=dev --no-audit --no-fund

if [ -f "$SRC/id_ed25519.pub" ]; then
  echo "== Installing your laptop's SSH key"
  mkdir -p "$HOME/.ssh"
  chmod 700 "$HOME/.ssh"
  cat "$SRC/id_ed25519.pub" >> "$HOME/.ssh/authorized_keys"
  sort -u -o "$HOME/.ssh/authorized_keys" "$HOME/.ssh/authorized_keys"
  chmod 600 "$HOME/.ssh/authorized_keys"
  rm -f "$SRC/id_ed25519.pub"
fi

# Keep the CPU awake while Termux runs, so events are not missed in standby.
termux-wake-lock || true
pgrep -x sshd >/dev/null || sshd

IP=$(ip -4 addr show wlan0 2>/dev/null | awk '/inet /{sub("/.*","",$2);print $2}' || true)
[ -z "$IP" ] && IP=$(ip -4 addr show eth0 2>/dev/null | awk '/inet /{sub("/.*","",$2);print $2}' || true)
echo
echo "== Done. From your laptop run:"
echo "   ssh -p 8022 ${IP:-<shield-ip>}"
echo "   then:  cd eufy-poc && node eufy-poc.js"
