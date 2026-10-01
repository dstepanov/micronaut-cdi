# Config Lite run evidence

Run on 2026-10-01 with MicroProfile Config TCK 3.1.2 and SmallRye Config 3.18.3, using the local Micronaut Core injection provider change and Jakarta Interceptors checkout.

| Verification | Result |
| --- | --- |
| Full upstream Config Lite suite | 378 tests, 34 classes, 0 failures/errors/skips |
| Focused ConfigProperties and CDIPropertyExpressions rerun | 10 tests, 0 failures/errors/skips |
| Synthetic declared array/generic types and enhanced qualifier regression | 1 test passed |
| Existing BCE/synthetic regressions | 20 tests passed |
| Synthetic archive admission | 4 tests passed |

`tests.tsv` summarizes the final full suite XML, including all expected invalid-deployment tests. `selected-tests.txt` and `test-discovery.tsv` record the binary-based upstream inventory. `deployment-index.tsv` preserves the full run's unique archive identities and startup results: `FAILED` is expected for invalid-deployment fixtures, whose test assertions passed. `artifacts-lite.tsv` records the resolved runtime dependency hashes from that same full run.

The parent project may retain the complete generated XML, compile/deployment diagnostics and Gradle logs under ignored build directories. The original vendor CDI Full extension was excluded for the Lite suite. There were no substitutions of upstream assertions or security tests.
