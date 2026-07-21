# Family Tree (offline, Bluetooth sync)

An Android app for building a family tree that lives entirely in a local
SQLite database (via Room), with peer-to-peer Bluetooth sync so relatives
can merge their trees when they're near each other — no server, no internet
required.

## How it's structured

```
app/src/main/java/com/familytree/app/
├── data/
│   ├── Person.kt              # entity: a person, PK is a UUID
│   ├── Relationship.kt        # entity: PARENT_OF / SPOUSE_OF / SIBLING_OF edges
│   ├── PersonDao.kt
│   ├── RelationshipDao.kt
│   ├── FamilyTreeDatabase.kt  # Room database (SQLite)
│   └── FamilyTreeRepository.kt# CRUD + merge logic for sync
├── bluetooth/
│   ├── SyncProtocol.kt        # JSON wire format + shared service UUID
│   └── BluetoothSyncManager.kt# RFCOMM host/connect + data exchange
├── ui/
│   ├── MainActivity.kt        # permissions + nav
│   ├── TreeViewModel.kt       # exposes DB + sync state to Compose
│   └── screens/
│       ├── FamilyTreeScreen.kt
│       ├── AddPersonScreen.kt
│       └── BluetoothSyncScreen.kt
└── FamilyTreeApp.kt           # Application: owns DB + repository singletons
```

## Why UUID primary keys

Both phones build their trees completely offline and independently. If IDs
were autoincrement integers, two different people created on two different
phones could both end up as "id 7", and merging would silently corrupt data.
UUIDs guarantee every record has a globally unique identity, so sync is a
safe operation in either direction.

## How Bluetooth sync works

1. Both phones must already be Bluetooth-**paired** with each other (done
   once, in Android's system Bluetooth settings).
2. In the app's **Sync** tab, one person taps **"Wait for connection"**
   (`BluetoothSyncManager.startHosting`) — this opens an RFCOMM server
   socket registered under a fixed SDP UUID (`FAMILY_TREE_SERVICE_UUID`).
3. The other person picks that paired device from the list, which calls
   `connectToDevice(device)`.
4. Once the socket is open, **both sides run the same routine**
   (`exchangeData`): each serializes its entire local tree to JSON
   (`SyncPayload`), writes it as one line, then reads the line the other
   side sends back.
5. Each device merges the incoming payload via
   `FamilyTreeRepository.mergeSnapshot`, using **last-write-wins** on the
   `updatedAt` timestamp, matched by UUID. New records are inserted; older
   duplicates are skipped; newer edits overwrite local ones.

This means either phone can be the "host" — the merge logic is symmetric,
so it doesn't matter who initiates the connection.

## Extending this further

- **Conflict UI**: currently last-write-wins is silent. You could surface
  conflicting edits (e.g. two different birth dates for the same person)
  for the user to pick between, rather than auto-resolving.
- **Photos**: `Person.photoUri` is a placeholder — you'd want to copy the
  actual image bytes over Bluetooth too (chunked, since RFCOMM streams are
  just bytes) or use a companion Bluetooth OPP/file transfer.
- **Bigger trees**: syncing full snapshots is simple but not efficient for
  very large trees — a delta/since-last-sync-timestamp exchange would scale
  better.
- **Tree visualization**: the current UI is a flat list with expandable
  parent/child sections. A true node-and-line tree diagram (custom Canvas
  drawing or a charting library) is a natural next step.
- **Migrations**: `fallbackToDestructiveMigration()` is fine for early
  development but will wipe user data on schema changes — replace with real
  `Migration` objects before shipping.

## Building

Open the `FamilyTree/` folder in Android Studio (Hedgehog or newer), let
Gradle sync, and run on a device or emulator with API 26+. Bluetooth sync
requires two physical devices (emulators don't support Bluetooth).
