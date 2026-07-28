# simulation -- the Axon Hunt harness

The bug-hunting suite for Axon Framework 5: seeded, concurrent, deliberately faulted workloads
over real framework components, judged by invariant checkers over a recorded operation history.

This module is behind a Maven profile, so `./mvnw verify` does not build it:

```bash
./mvnw -q -Phunt -pl simulation -am test > /tmp/hunt.log 2>&1; echo "EXIT=$?"
```

Green prints nothing; judge by the exit code.

**Read `HUNT.md` at the repository root before changing anything here.** It carries the
vocabulary, the structure map, the command index and the four rules that bind this module --
including the two that matter most: framework code is never patched, and no test here is ever
retried or disabled.

The invariants this module judges by, the history schema it records, and the recipes for adding
an invariant, scenario, fault, workload or backend are in `formal/INVARIANTS.md`.
