---
description: Run the CI gates quietly (content | test | android | all) and report only failures
argument-hint: [content|test|android|all]
---
Run `scripts/gates.sh $ARGUMENTS` (default `content`). It logs to `build/gates/*.log` and prints one
line per step. On FAIL, read only the printed lines and the first error in the named log. Do not
cat full logs. Report the command output and exit status verbatim; never claim a pass without it.
