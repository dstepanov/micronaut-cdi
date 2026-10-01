# CDI Lite differential compatibility fuzzing

This opt-in module compares CDI behavior with Weld. It covers generated type resolution, qualifier members, generic events, interceptor arguments, handle lifecycle sequences, and independently compiled source mutations. The findings and saved evidence are in [FINDINGS.md](FINDINGS.md).

The normal build does not include this module or depend on Weld. Enable it with `-PcompatibilityFuzz`.

## Run the campaign

From the repository root, use the interceptor revision used for the recorded campaign:

```sh
git clone https://github.com/dstepanov/micronaut-jakarta-interceptors.git build/fuzz-interceptors
git -C build/fuzz-interceptors checkout d9307e5
./gradlew :micronaut-compatibility-fuzz:test \
  -PcompatibilityFuzz \
  -PjakartaInterceptorsDir=build/fuzz-interceptors
```

Reuse that checkout when it already exists. `-PfuzzWeldVersion=5.1.7.Final` selects the older CDI 4.0 reference. The default is Weld 6.0.4.Final, matching the project's CDI 4.1 API. `-PfuzzSeed=12345` varies the qualifier and handle sequences; the recorded seed is `0xCD140`.

To extend the type grammar, edit and run:

```sh
python3 compatibility-fuzz/generate.py
```

The generated `TypeCorpus.java` is checked in, so Python is not needed to run the tests.

After a successful run, `python3 compatibility-fuzz/snapshot.py --label NEW_RUN_LABEL` preserves the primary campaign's evidence outside the ignored build directory. Pass `--weld <version>` when preserving an alternate reference run.

## Results and controls

Each comparison records `family`, `case`, `Weld result`, `Micronaut result`, and `MATCH` or `DIFF` as tab-separated fields. The output directory is `compatibility-fuzz/build/campaigns/<Weld version>/`:

- `results.tsv`: behavioral and bean-type metadata comparisons.
- `source-results.tsv`: compiler/deployment acceptance and runtime source mutations.
- `source-mutations/`: input Java sources, compiler diagnostics, deployment rejections, and runtime traces.
- `artifacts.tsv`: resolved runtime and processor artifacts with SHA-256 hashes.

`REJECT` in source results means rejection at compilation or container initialization; both are acceptable rejection stages for this compile-time implementation. `ERROR:*` means initialization succeeded and using the bean failed. Plain Java compilation failures and processor/loader failures are harness errors and fail the campaign. Valid interceptor fixtures are positive controls, and all type and interceptor deployments must return the expected number of observations.

Weld and Micronaut bootstraps are selected explicitly. The behavioral corpus uses the same fixture classes; the source corpus compiles separately with plain Java for Weld and with all Micronaut processors for Micronaut. Each source deployment uses a fresh class loader and clean generated output. Neither container calls the other container's provider, and the campaign does not call the TCK adapter's extra deployment validator.

Tests fail on harness errors. Recorded behavior differences remain data for review, so an observational campaign can pass while reporting differences. This is bounded grammar and state-machine fuzzing, not a coverage-guided or exhaustive certification suite.
