# ThorSecondScreen Design Spec

How each part works: module structure, the socket algorithms with their parameters, interfaces, error handling, device constraints and the reasons behind the main decisions. Written from reading the source at commit `372351b` on 2026-10-22. Nothing was built, run or tested. Anchors are `file:line` and use the prefixes defined in the [application spec](../application-spec/README.md#anchor-convention). **Unverified** marks anything the code alone cannot confirm, especially behaviour of the mod.

## Files

| # | File | Subsystem / topic |
|---|---|---|
| 01 | [architecture-modules.md](01-architecture-modules.md) | Modules of the Android app, the Linux companion, the Decky plugin and the tools; ownership and state |
| 02 | [protocols-algorithms.md](02-protocols-algorithms.md) | Framing, reconnect policy, icon requests, asset and map delivery, timing, gesture and layout algorithms |
| 03 | [data-flow.md](03-data-flow.md) | Mermaid diagrams: end-to-end flow, connect sequence, container click round trip, HUD handoff |
| 04 | [interfaces-error-handling.md](04-interfaces-error-handling.md) | Public surfaces of each module; error handling, fallbacks, port-conflict detection |
| 05 | [constraints-decisions.md](05-constraints-decisions.md) | Device and platform constraints; design decisions and what was rejected |
| 06 | [roadmap.md](06-roadmap.md) | Open items, from the code and the docs, ordered by how much they matter to the player |

## Conventions

- Terms used here are defined in the [application spec glossary](../application-spec/06-testing-limitations-glossary.md#glossary).
- "Client" means either the Android app or the Linux companion. "Mod" means the Fabric mod, which is outside this tree.
- Numbers in tables are the values in the code at the anchored line. Derived numbers (for example a delay sequence) show the arithmetic.
