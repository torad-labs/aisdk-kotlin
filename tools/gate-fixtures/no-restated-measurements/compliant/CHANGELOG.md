# Changelog

## Unreleased

- HD-era hardening summary: measured coverage, gate, and ratchet state now lives
  in `dev/measurements.toml` and is cited by key instead of copied into docs:
  `[meas: coverage_line_percent]`, `[meas: coverage_instruction_percent]`,
  `[meas: coverage_branch_percent]`, `[meas: kover_branch_floor_percent]`,
  `[meas: ast_grep_rule_count]`, `[meas: gate_fixture_harness_checks]`,
  `[meas: public_data_class_floor]`, and `[meas: ci_gate_wall_clock_s]`.
