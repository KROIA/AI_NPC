# Issues

Fields: `ID` · `Title` · `Description` · `Possible solution` · `Priority` (critical / high / medium / low — see priority rubric). Sorted by priority.

## Hotfix

(none)

## Backlog

### I-001 — Recording/feature constants duplicated by hand between Java and Python
- **Description:** LABELS / GOALS / KEYS are defined separately in the Java mod and in the Python training code. If one side changes, the model's inputs/outputs silently misalign.
- **Possible solution:** single source (e.g. Java writes the tables into the dataset header or a shared JSON that Python reads), or a startup check that compares both lists.
- **Priority:** medium

### I-002 — `tools/make_test_recording.py` docstring says format 3, code writes format 2
- **Description:** Docstring/code mismatch found during the init scan.
- **Possible solution:** fix the docstring (or bump the writer if format 3 is intended).
- **Priority:** low
