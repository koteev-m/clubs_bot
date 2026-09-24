# CLB-91 portable native-adapter candidate

This directory is the complete small source closure for the candidate native experiment. It does not contain a runtime image, package archives, local binaries or previous run results. Candidate pins are not production approval.

Reference provenance uses `bundle:` identifiers relative to this directory. Readers open only named files in the two reference directories. Complete ledger hashes are embedded in codegen/native-driver and prepare-native-inputs; editing a leaf and its adjacent ledger alone fails. The outer publication digest and exact commit must be independently checked before executing these readers. PROVENANCE.json preserves accepted archive/content ancestry; this relabelling is not new supplier authentication.

The builder takes mandatory `--image sha256:...` and `--archive ABSOLUTE_PATH` arguments. CI preparation supplies the exact image ID only after matching the reference manifest and package list. The archive is size/hash checked, with no-follow and identity rechecks, before any build. There is no path search, implicit image, source-string rewriting or fallback. Output paths containing spaces are supported; comma/newline archive paths are refused because Docker mount syntax would be ambiguous.

Local nonprivileged tests, from this directory:

```sh
python3 -I -S -B tests/test-codegen.py
python3 -I -S -B tests/test-driver.py
python3 -I -S -B ci/test-recipe.py
```

The driver suite can additionally receive `--runtime-tar /explicit/preserved/rootfs.tar`; missing optional input is an explicit skip, never a local-path search or download. Codegen runs only in disposable test copies. Test reports go to stdout. The runtime-tar control is not a native execution test.

Prepared workflow/harness changes are distributed in the outer publication package. Future native preparation, static compilation, root helper and three-job workflow scope are specified in ci/FUTURE-NATIVE-SCOPE.md. None is authorized by packaging tests. The fixed adapter/helper/generated contract and runtime bytes are unchanged.
