#!/usr/bin/env bash
# Claude Code cloud sessions only (called from .claude/settings.json when CLAUDE_CODE_REMOTE=true).
# Optionally fetches a private config repo and runs its bootstrap. Configure it with two
# environment variables on the cloud environment; without them this does nothing.
#   CLAUDE_CONFIG_REPO   owner/name of the private repo
#   CLAUDE_CONFIG_TOKEN  token with access to that repo only
set -u
if [ -z "${CLAUDE_CONFIG_REPO:-}" ] || [ -z "${CLAUDE_CONFIG_TOKEN:-}" ]; then
  echo "cloud-start: no private config repo configured; using this repository's config only."
  exit 0
fi
dest="$HOME/claude-config"
if [ -d "$dest/.git" ]; then
  git -C "$dest" pull -q --ff-only || echo "cloud-start: could not update $dest; using the existing copy."
elif ! git -c credential.helper= clone -q \
    "https://x-access-token:${CLAUDE_CONFIG_TOKEN}@github.com/${CLAUDE_CONFIG_REPO}.git" "$dest" 2>/dev/null; then
  echo "cloud-start: could not clone the private config repo; using this repository's config only."
  exit 0
fi
if [ -x "$dest/cloud/bootstrap.sh" ]; then "$dest/cloud/bootstrap.sh"; fi
exit 0
