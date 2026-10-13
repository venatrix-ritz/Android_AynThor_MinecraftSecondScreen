#!/usr/bin/env bash
set -euo pipefail

THOR_HOST="${1:-192.168.0.41}"
PLUGIN_DIR="/home/armada/homebrew/plugins/thor-mc-hud"

echo "==> Deploying Minecraft HUD Decky Plugin to ${THOR_HOST}..."

ssh "${THOR_HOST}" "sudo mkdir -p '${PLUGIN_DIR}/dist'"

scp plugin.json "${THOR_HOST}:/tmp/plugin.json"
scp package.json "${THOR_HOST}:/tmp/package.json"
scp main.py "${THOR_HOST}:/tmp/main.py"
scp dist/index.js "${THOR_HOST}:/tmp/index.js"

ssh "${THOR_HOST}" "
    sudo mv /tmp/plugin.json /tmp/package.json /tmp/main.py '${PLUGIN_DIR}/'
    sudo mv /tmp/index.js '${PLUGIN_DIR}/dist/index.js'
    sudo chown -R root:root '${PLUGIN_DIR}'
    sudo chmod -R 755 '${PLUGIN_DIR}'
    echo '==> Restarting plugin_loader.service...'
    sudo systemctl restart plugin_loader.service
"

echo "==> Decky plugin deployed! Check the '...' Quick Access Menu in Steam."
