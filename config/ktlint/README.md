# Ktlint recovery baseline

These baselines snapshot the ktlint 1.3.1 findings already present on
`main` at `88f4815661bc1dcaf4d8b6619f40621935923468`.

No ktlint rule is disabled. New findings remain blocking, while existing entries
can be removed incrementally when the corresponding source is edited. Do not
regenerate these files to absorb new violations.

`baseline-build-scripts.xml` covers the root and module Gradle Kotlin scripts;
the remaining files cover Kotlin sources per JVM module.
