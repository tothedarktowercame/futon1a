# Invariants

This document defines cross-layer invariants that bind the stack together.

## Counter-Ratchet

Counts for key collections must not drop unexpectedly. A drop is treated as a
Layer 2 integrity failure.

Implementation: `src/futon1a/core/invariants.clj`.
