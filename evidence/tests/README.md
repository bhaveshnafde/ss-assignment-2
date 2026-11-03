# Automated tests

`RUN_TESTS=1 evidence/collect-all.sh` runs `mvn verify` in every service and saves:

| File | Contents |
| --- | --- |
| [summary.txt](summary.txt) | PASSED or FAILED per service |
| `<service>-tests.txt` | One line per test class: tests run, failures, errors |
| `<service>-mvn-verify.log` | The full Maven output |

171 tests across the six services; names start with the rule they prove (`BR2_…`, `F1_…`, `N1_…`), so
the class files double as a traceability list. See the report's Testing section for the mapping.
