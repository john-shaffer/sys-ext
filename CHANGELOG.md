## Unreleased

- Add a `first-cycle` function to find a cycle in the system.
  Systems are not supposed to contain cycles, but when they
  do it is useful to detect it and produce an informative
  message.
- Add `sys-ext.graph/cycle-error` function to produce an
  [[ExceptionInfo]] from a cycle path.

## v0.1.0 (2024-04-17)

- Add call component that calls a function when started.
- Add merge component that merges maps when started.
- Add expand-inline-defs function. This expands component defs that
  are found inside another component's config. This is convenient for
  small data adjustments like type conversions and lookups.
