# Playbook: known (the repo already names this ROM)

Identify found the SHA-1 (or, for a DS cart, its header) in the repo. Nothing to add for recognition. Find out what the user
actually wants:

- **A feature it lacks**: read its row in docs/ROM_FEATURES.md (a `—` or `N/A` column, and the notes under the table) and
  follow that feature's own section in CLAUDE.md / docs/DEVELOPMENT.md. Use its existing fixtures (host_roms.conf keys,
  shown by identify) and add the ones the feature needs (`_battle`, `_trainer`, a walking capture for EXPAND). The row
  changes in the same commit.
- **A bug on it**: reproduce headless from its fixture or the user's state (`loadstate`, same ROM build only) before
  changing anything.
- **A second dump of the same game** (another file, same hash): nothing to do. If it sits in the wrong place or has the
  wrong name, say so.
- **A DS cart supported by its header but a different dump** (a `[b]` set's): it already runs. Its library name falls back
  to the file's when the SHA-1 isn't the one in ports.toml / GameTitles.

Finish with `make rom-checklist ROM=...` anyway: a known game can still be missing from a book (an old row without its
newest version, a stale compat.json).
