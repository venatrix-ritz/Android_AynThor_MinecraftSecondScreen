# 03 - Data flow

Diagrams are Mermaid. Labels containing parentheses or colons are quoted. The mod's internals are shown only as far as this tree observes them.

## End-to-end

```mermaid
flowchart LR
  Mod["Fabric mod, out of tree"] -->|"JSON lines, 127.0.0.1:48291"| Conn["HudRepository connection loop"]
  Conn --> Parse["parseAndPublish by type"]
  Parse --> Flows["StateFlows: hudState, container, mapTile, chatMessages, bindings, portConflict"]
  Parse --> Caches["assetCache, iconCache"]
  Flows --> UI["SecondScreenApp tabs and HudScreen"]
  Caches --> UI
  UI -->|"user taps and drags"| Send["sendCommand, one coroutine per line"]
  Send -->|"text lines"| Mod
  Settings["SharedPreferences settings"] --> UI
  UI -->|"HUD: element keys"| Send
```

## Connect and hello

```mermaid
sequenceDiagram
  participant App as Android HudRepository
  participant Mod as Mod socket
  App->>Mod: connect 127.0.0.1:48291 (1000 ms timeout)
  alt connection accepted
    Mod-->>App: hello (mod, protocol)
    Note over App: sawHello true, silent count 0, failed count 0, portConflict false
    Mod-->>App: asset lines (one per catalogue key)
    Mod-->>App: bindings line
    Mod-->>App: chat backlog lines
    Mod-->>App: container line (if a handler is open)
    loop each tick
      Mod-->>App: snapshot line (no type)
      Mod-->>App: map line
    end
    App->>Mod: HUD elements line (HUD:...)
    App->>Mod: CREATIVE, ICON, BINDINGS as requested
  else refused or closed without hello
    Note over App: silent count +1; after 3, portConflict true
    App->>App: wait by ReconnectPolicy, then retry
  end
```

The order of asset, bindings, chat and container lines follows the simulator's replay order (`tools/hud_sim.py:865-881`) and is **unverified** against the real mod.

## Container click round trip

```mermaid
sequenceDiagram
  participant User as Player
  participant UI as InventoryScreen
  participant Repo as HudRepository
  participant Mod as Mod socket
  User->>UI: tap or drag on a placed cell
  UI->>Repo: sendSlotClick(syncId, slot, button, action)
  Repo->>Mod: "SLOT:syncId,slot,button,ACTION"
  Note over UI: nothing drawn yet
  Mod-->>Repo: container line (new state, if it changed)
  Repo-->>UI: container StateFlow update
  UI-->>User: redrawn from the game's state
```

## HUD element handoff

```mermaid
flowchart TD
  Toggle["Settings toggles an element"] --> Flow["snapshotFlow of hidden keys"]
  Flow --> Send["HUD: comma list, full set every time"]
  Send --> Mod["mod re-enables hidden vanilla elements on the top screen"]
  Reconnect["isConnected turns true"] --> Flow
  Mod -.->|"offhand key ignored by design"| Mod
```

The HUD key list is sent whenever the connection comes up and whenever a toggle changes (`KT/SecondScreenApp.kt:215-219`). The mod's reaction is documented in `CLAUDE.md` and **unverified** here.

## Container takeover

```mermaid
stateDiagram-v2
  [*] --> Idle
  Idle --> Items: container with screenOpen and kind enabled
  Idle --> Creative: creativeScreen true and kind enabled
  Items --> Idle: container closed, restore tab if no manual choice
  Creative --> Idle: container closed, restore tab if no manual choice
  Items --> Items: manual tab change clears memory
```

Kind is `ContainerKind.of(handlerType, isPlayerInventory, creativeScreen)` (`KT/settings/ContainerKind.kt:83-97`). Creative always maps to the Inventory kind for its toggle (`:93`).

## Icon request

```mermaid
flowchart TD
  Frame["composition asks requestIcon"] --> Cached{"cached?"}
  Cached -- yes --> Use["return bitmap, touch LRU"]
  Cached -- no --> Air{"air or empty id?"}
  Air -- yes --> Nothing["return null"]
  Air -- no --> Failed{"failed within 60 s?"}
  Failed -- yes --> Nothing
  Failed -- no --> Pending{"pending within 3 s?"}
  Pending -- yes --> Nothing
  Pending -- no --> Writer{"connected?"}
  Writer -- no --> Nothing
  Writer -- yes --> Send["mark pending, send ICON id"]
  Send --> Nothing
```
