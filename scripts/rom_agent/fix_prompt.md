The driver ran its checks on your work and some failed. Fix what they show, keeping to the brief's rules (no full
test runs, no git commits / pushes, nothing that mustn't be committed, every address proven). Don't drop a
working feature to make a check pass unless it was wrong; if a failure isn't yours (a test that already failed on
the base branch), leave it and say so in the report's `notes_for_reviewer`.

{{FAILURES}}

When the checks pass for you (`make test-quick` and whichever of the above you can run), update
`{{REPORT_PATH}}` to match the work as it now stands (`tests_run`, `not_done`, `notes_for_reviewer`), then stop.
