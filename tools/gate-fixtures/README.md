# Gate Fixtures

Add one directory per gate:

```text
tools/gate-fixtures/<gate>/cmd.txt
tools/gate-fixtures/<gate>/violation/
tools/gate-fixtures/<gate>/compliant/
```

`cmd.txt` is run once from each case directory. The runner provides
`REPO_ROOT`, `GATE_NAME`, `CASE_KIND`, `FIXTURE_DIR`, and `GATE_FIXTURE_DIR`.
The compliant case must exit `0`; the violation case must exit nonzero.
