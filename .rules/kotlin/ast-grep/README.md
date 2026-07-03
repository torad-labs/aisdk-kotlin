# Kotlin ast-grep Rules

Structural enforcement for `torad-aisdk`. Based on the torad-toolkit standard.

## Structure

```
.rules/kotlin/ast-grep/
├── rules/        # LAW - blocking, always scanned (41 rules)
├── rules-style/  # Opt-in tenets - NOT in sgconfig (32 rules)
├── codemods/     # Mechanical migrations with fix: clauses
├── normalize/    # Stop-time auto-formatters
├── utils/        # Shared rule fragments
├── tests/        # Native ast-grep test fixtures
├── sgconfig.yml  # ast-grep config (only scans rules/, codemods/, normalize/)
└── registry.json # Autofix enrollment registry
```

## Usage

```bash
# Scan with LAW rules (blocking)
ast-grep scan --config .rules/kotlin/ast-grep/sgconfig.yml src/

# Scan with a specific opt-in style rule
ast-grep scan --rule .rules/kotlin/ast-grep/rules-style/no-inline-json-instance.yaml src/
```

## Rule Severity

- **`rules/`**: `severity: error` — violations block edits and fail CI
- **`rules-style/`**: `severity: warning` — opt-in, not scanned by default

## Adding Rules

1. Create `<rule-id>.yaml` in the appropriate directory
2. For LAW rules: add to `rules/`, ensure `severity: error`
3. For style tenets: add to `rules-style/`, use `severity: warning`
4. Test with `ast-grep scan --rule <path> src/` before committing
