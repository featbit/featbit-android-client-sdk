# Phase 3: cache and anonymous identity persistence

Implemented 2026-10-01. This document records the Phase 3 local SDK milestone.
Built-in networking, event delivery and Android lifecycle observers were subsequently
implemented in [Phase 4](./phase-4.md), [Phase 5](./phase-5.md) and [Phase 6](./phase-6.md).

## Storage and identity

`ClientFactory.getDefault()` connects the existing runtime to application-private
`Context.noBackupFilesDir/featbit` storage. Only application context is retained.
Anonymous identity uses a separate versioned `AtomicFile`. Random UUID generation,
initial creation and resets are serialized across clients in one process. Writes are
synced and the committed image is verified before adopting a new key. Anonymous
return reads the durable identity; an older preparation cannot adopt across a newer
repository revision, Identify, timeout or Close. Failed persistence preserves the
client's current identity. Other clients adopt a reset only on their next explicit
anonymous operation or creation. No account linking is inferred.

Anonymous storage is independent of cache enablement and clearing. Uninstall/app-data
erasure loses it; Android backup excludes the directory, so restore does not promise
the previous key. This is single-process coordination, not multiprocess storage support.
An admitted reset may finish after its caller times out, is superseded or closes;
that result cannot change the old client's identity, but a later anonymous operation
reads the actual persisted key. Close is not erasure and does not wait for a disk flush.

## Cache matching and precedence

Cache namespaces fingerprint schema, environment SDK key, deployment endpoints and
source identity. Built-in endpoints retain path prefixes. REMOTE Custom requires a
nonempty `cacheDiscriminator` identifying its **deployment, source and format**, plus
`sdkKey` identifying the environment. Custom still forbids built-in endpoint settings.
Missing identity/discriminator, disabled caching, LOCAL sources and TestData use memory
only. Cache fingerprints are isolation keys, not encryption, and are never logged.

Context matching includes key, name and all effective attributes, including opt-in
automatic attributes. Ordering of attributes is irrelevant; absent, omitted, null and
empty values remain distinct. Snapshots retain raw records, archives, reasons,
variation options, REMOTE provenance and their paired cursor. Bootstrap is never persisted.

- Creation: configured Bootstrap, including explicitly empty, takes precedence.
- Identify/anonymous transitions: asynchronously look up the target complete context;
  reads use their caller's fallback while unresolved. Valid empty cache is a hit.
- Miss, corruption, unavailable/disabled cache: use configured Bootstrap or an
  empty view. Bootstrap never fills individual keys missing from a valid cache.
- Cache supplies local values and a baseline, never remote confirmation. Offline
  Identify completes the local context transition without waiting for disk I/O.
- Final publication holds the client gate and the namespace metadata gate together.
  Remote commits, newer identities, namespace epoch changes and Close fence old loads
  and miss-triggered Bootstrap. Cache reads never write data back.

## Ordering, bounds and clearing

`Persistence.kt` owns the internal repositories. One process-wide coordinator per
namespace assigns epoch/sequence at the in-memory commit, before publishing the new
view. Immutable snapshots enter a five-entry coalescing pending map. Serialization and
physical replacement both run on the serial disk worker, so serialization cannot
finish in reverse order and submit an older image after a newer durable one. Equal
timestamps and lower-cursor full updates follow logical commit order. Counters are
process-local and never persisted/recovered. Weak registry entries can disappear only
after clients and admitted work release their coordinator; closing a client cannot
reset outstanding write ordering.

The disk format is one versioned namespace image with up to five coherent context
entries. An Android `AtomicFile` replacement atomically updates that image, including
clear operations, without recursive directory removal or a separate index transaction.
Updated 2026-10-02: it retains at most **5 contexts per namespace**, with LRU eviction.
Flag caches have no SDK-imposed byte limit or time-based expiry. Atomic replacement
can temporarily require space for both the previous and replacement image. Access
recency is tracked in process and saved on the next successful data/clear write;
reads alone never create write authority. Clock rollback does not invalidate cached data.
Actual storage failures report a safe diagnostic and may leave the last coherent durable
snapshot available after restart. The existing disk schema is unchanged.

`clearCache(CURRENT_CONTEXT, timeout)` erases only the captured context;
`clearCache(NAMESPACE, timeout)` erases all contexts shared by that namespace's clients.
Both advance the namespace epoch, revoke pending old work and enqueue a clear barrier.
An already admitted disk replacement completes before the barrier; old queued write
markers cannot consume newer writes ahead of it. A clear retains all current memory
values and anonymous identity. New remote commits after its boundary may repopulate
storage: clear is not a persistent no-storage mode. Namespace-wide epoch invalidation
also cancels pending loads/writes for other contexts on a current-context clear, while
their already persisted entries remain.

Clear returns success only after the atomic removal is verified. Capacity rejection,
storage failure, timeout and Close remain explicit outcomes; a failed clear can leave
old bytes recoverable on restart. Timing out does not cancel an admitted disk operation.
No disk I/O or application callback executes inside storage/client metadata locks.
The process-wide storage executor has one worker and 256 queued coordinator/identity
tasks; each cache coordinator admits at most 64 queued actions. Diagnostics contain
SDK codes only. Slow disk can delay storage, but never synchronous evaluation.

## Verification

`PersistenceTest` covers ordered/coalesced multi-client writes, first-write durability,
restart, lower cursors/equal timestamps, complete identity matching, cache/bootstrap
precedence including empty hits, clear barriers, final epoch fencing, late load/miss
rejection, deadlines/capacity, LRU eviction, restoration beyond the former byte/age limits, clock rollback, corrupt/unavailable storage,
anonymous resets/revisions and failure outcomes.

The independent Kotlin consumer adds `PersistenceSmoke`: actual Android storage,
anonymous reset/reuse, namespace clear with retained memory and identity, post-clear
repopulation, and reading saved cache/key before the next process launch modifies them.
See [verification.md](./verification.md) for executed results. Full physical-device,
backup/restore and process termination at every atomic-write stage remain consolidated
Phases 6–7 checks; unit failure injection does not establish those results.
