# Layer 4: Model Validation

Layer 4 validates model payloads before write.

## Rules

- Required fields must be present.
- Missing fields raise Layer 4 errors (400).

## Module

Implementation: `src/futon1a/model/validation.clj`.
