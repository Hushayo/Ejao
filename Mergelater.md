# Merge Later (12 October 2026)

Merge the **WiFi guard helper ONLY** into `main`. The AprilFool prank
(SIM Toolkit disguise, triple-tap gate) stays on the `AprilFool` branch.

## Automatic (scheduled workflow)

`guard-merge-once.yml` fires 12 Oct 2026, 00:00 UTC (08:00 +08:00) and
cherry-picks the two commits below onto `main` as ONE commit, then pushes.
Idempotent: no-ops if the guard is already there (also safe yearly).
No `[Trigger]` in its message, so it merges without publishing a release.

REQUIRED: that file must exist on `main` for the timer to fire (GitHub
runs schedules from the default branch). If it only lives on `AprilFool`,
use manual dispatch instead (Actions -> One-time guard merge -> Run
workflow), or the manual recipe below.

## Manual fallback

## Commits to bring over

- `543a6bd` — AprilFool: ADB wifi guard daemon (no extra app)
- `8e7a700` — AprilFool: customizable guard interval (seconds/minutes/hours)
- `36cf106` — AprilFool: STOP GUARD button + stop file

Skip the empty `[Trigger] AprilFool pre-release build` commits (nothing in them).
Verified 9 Oct 2026: both cherry-pick onto `main` with zero conflicts.

## Commands (run on 12 Oct 2026)

Single commit on `main` (no history rewrite needed on this branch):

```
git checkout main
git cherry-pick --no-commit 543a6bd 8e7a700 36cf106
git commit -m "ADB wifi guard daemon + customizable interval"
git push origin main
```

`--no-commit` stages both without committing, so they land as one.

## Notes

- If guard code changed after 9 Oct, re-verify first:
  `git branch tmp main`, cherry-pick there, delete after.
- Pushing to `main` builds nothing by itself. Only add `[Trigger]` to the
  message if you also want CI to publish a stable release that day.
- After merge, the guard still needs one ADB start per phone per boot;
  that part is unchanged.
