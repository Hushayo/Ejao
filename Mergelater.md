# Merge Later (12 October 2026)

Merge the **WiFi guard helper ONLY** into `main`. The AprilFool prank
(SIM Toolkit disguise, triple-tap gate) stays on the `AprilFool` branch.

## Commits to bring over

- `543a6bd` — AprilFool: ADB wifi guard daemon (no extra app)
- `8e7a700` — AprilFool: customizable guard interval (seconds/minutes/hours)

Skip the empty `[Trigger] AprilFool pre-release build` commits (nothing in them).
Verified 9 Oct 2026: both cherry-pick onto `main` with zero conflicts.

## Commands (run on 12 Oct 2026)

```
git checkout main
git cherry-pick 543a6bd 8e7a700
git push origin main
```

## Notes

- If guard code changed after 9 Oct, re-verify first:
  `git branch tmp main`, cherry-pick there, delete after.
- Pushing to `main` builds nothing by itself. Only add `[Trigger]` to the
  message if you also want CI to publish a stable release that day.
- After merge, the guard still needs one ADB start per phone per boot;
  that part is unchanged.
