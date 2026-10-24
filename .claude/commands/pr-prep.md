---
description: Pre-PR checklist for a pkm/PKM-<n> branch (gates, clean assets, no stray reports)
---
1. `git branch --show-current` must be `pkm/PKM-<n>`; never push to a protected branch or force-push.
2. `scripts/gates.sh all`. Paste its output into the PR body under "Verification".
3. `git status --short`: `assets/` must be clean; `git checkout -- reports/` if the gates only
   rewrote reports you did not intend to change.
4. Docs/tools-only change: say so in the PR (A5 rule). UI change: attach an emulator screenshot.
5. Open the PR with `.github/pull_request_template.md` filled in; link the issue (PKM-<n>).
