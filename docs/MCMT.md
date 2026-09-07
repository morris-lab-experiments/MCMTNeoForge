# Multi-core tick processing (MCMT)

MCMT spreads the server tick across a pool of worker threads instead of running it all on the
server thread. It is **off by default** and must be switched on deliberately.

Read the whole of [What you are trading away](#what-you-are-trading-away) before enabling it on a
server whose world you care about. This is not a drop-in optimisation; it changes the guarantees
the game is written against.

## What it parallelises

Four tick loops are dispatched to a `ForkJoinPool` instead of being run inline:

| Hook | Loop | Config switch |
| --- | --- | --- |
| H1 | Each level's tick (`MinecraftServer.tickChildren`) | `disableWorld` |
| H2 | Each entity's tick (`ServerLevel.tick`) | `disableEntity` |
| H3 | Each block entity's tick (`Level.tickBlockEntities`) | `disableBlockEntity` |
| H4 | Each chunk's environment tick (`ServerChunkCache.tickChunks`) | `disableEnvironment` |

Everything outside those loops still runs on the server thread: command execution, networking,
player join and leave, save, autosave, and the level tick events.

Each loop ends at a barrier, so a tick does not finish until every task it dispatched has
finished. Ticks do not overlap each other, and levels do not drift apart in tick count.

## Enabling it

Edit `config/neoforge-mcmt.toml`:

```toml
[general]
    disabled = false
```

Or at runtime, without a restart:

```
/mcmt config disabled false
/mcmt save          # persist it; runtime changes are otherwise lost on restart
```

Runtime changes take effect on the next tick, except `paraMax` and `paraMaxMode`, which need
`/mcmt restart` to rebuild the pool.

## Configuration

### `[general]`

| Key | Default | Meaning |
| --- | --- | --- |
| `disabled` | `true` | Master switch. When true every tick runs inline, exactly as vanilla. |
| `opsTracing` | `false` | Record the name of each in-flight task so crash reports can list them. Costs allocation per task; switch it on only while chasing a crash. |

### `[parallelism]`

| Key | Default | Meaning |
| --- | --- | --- |
| `paraMaxMode` | `STANDARD` | `STANDARD` clamps `paraMax` to the processor count, `OVERRIDE` uses it verbatim, `REDUCTION` subtracts it from the processor count. |
| `paraMax` | `0` | The pool's parallelism target; `0` or `1` means "all processors". Never sizes below two. |

`paraMax` is a target for how many threads should be *runnable*, not a cap on how many threads
exist. A `ForkJoinPool` may exceed it to replace a worker that has blocked — on a chunk lock, say
— so the live thread count can sit above `paraMax` under contention. It should stay in the same
region as it, though: if you find it in the hundreds, that is a bug, not tuning.

**Do not set `paraMax` below 4.** Dispatching a tick task costs something, and a busy level
dispatches tens of thousands of them per tick. Measured on a 32-core machine against a load that
took 61 ms single-threaded:

| `paraMax` | mean tick | |
| --- | --- | --- |
| off | 60.9 ms | |
| 2 | 67.4 ms | **slower than not using MCMT at all** |
| 4 | 24.2 ms | 2.5× |
| 8 | 16.5 ms | 3.7× |
| 16 | 15.5 ms | 3.9× |
| 32 | 16.7 ms | 3.6× |

At two workers the dispatch overhead is larger than the parallelism is worth. The gain flattens
once the pool is big enough to cover the work — past that, more workers mostly buy contention.
Your own numbers will differ; the shape is the point.

> **These numbers predate the hopper fix and overstate the gain.** That load was mostly hoppers,
> which then ran unsynchronised — and were duplicating items, so they were doing less work than
> correctness requires. Hoppers are now serialised against their neighbours. The shape of the curve
> against worker count still holds; the multipliers do not, and have not been re-measured against a
> load that is not hopper-dominated. See *Hoppers are slow under MCMT, on purpose*.

`REDUCTION` is the useful one on a machine that is doing anything else. Minecraft's own
background executor generates chunks and does world I/O on the same CPUs, so handing every core
to the tick pool can make chunk loading worse while making the tick faster.

### `[hooks]`

Five switches, all `false` by default, each of which sends one loop back to running inline. They
exist for bisection: if something misbehaves with MCMT on, turn them off one at a time to find
which loop is responsible. `disableChunkProvider` additionally turns off the concurrent chunk
cache fast path.

### `[serdes]`

Not every tick can run concurrently with its neighbours. The serdes layer decides, per class,
which ones need serialising and how:

- **free** — runs on any worker with no coordination.
- **chunk-locked** — the tick takes a lock over the chunks around its position, so two of these
  near each other serialise while distant ones still run in parallel.
- **single execution** — runs one at a time, globally, for the current tick.

| Key | Default | Meaning |
| --- | --- | --- |
| `vanillaDefault` | `FREE` | What a vanilla class no rule mentions gets: `FREE`, `POS_LOCK` (its block and the six around it) or `CHUNK_LOCK`. |
| `chunkLockModded` | `true` | Chunk-lock every entity and block entity whose class is not vanilla Minecraft. |
| `blockEntityWhiteList` / `entityWhiteList` | empty | Run free. Wins over everything below, including `chunkLockModded`. |
| `blockEntitySingleThreadList` / `entitySingleThreadList` | empty | Run single-execution. |
| `blockEntityBlackList` / `entityBlackList` | empty | Chunk-lock. |

The three lists are consulted in that order, so the whitelist narrows either of the others by
exception. A class named on both of the other two is chunk-locked *and* single-executed by an owner
who evidently is not sure, so the stricter of the two wins.

`chunkLockModded = true` is the safe default and should stay on unless you have a specific reason.
Modded tick code has never had to be thread-safe before, so assuming it is not is the only
defensible starting position. Whitelisting a mod's classes is how you buy back the throughput,
one mod at a time, once you have reason to believe that mod is safe.

Prefer the blacklist to the single-thread list. Chunk-locking costs nothing when the two ticking
objects are far apart, which is the usual case; single execution costs the whole parallelism of
that class everywhere in every dimension. Reach for it only when position-scoped locking cannot
help in principle — a tick that walks a global registry, or moves objects between dimensions.

### `vanillaDefault`, and what it is honestly worth

`FREE` is a bet that every unsafe vanilla class is named in MCMT's code. That bet has been wrong three
times — hoppers, item-entity merging and mob loot pickup each duplicated or destroyed items — and each
time it failed *silently*, because the list of unsafe classes was assembled from crashes and none of
these ever crashed.

So you can invert it. Measured on a world of 4096 hoppers, a merge cluster and 200 item-collecting mobs:

| `vanillaDefault` | tick rate | hopper array | item merging | mob pickup |
| --- | --- | --- | --- | --- |
| `FREE` | 128/s | correct | **loses items** | **duplicates items** |
| `POS_LOCK` | 86/s | correct | correct | **duplicates items** |
| `CHUNK_LOCK` | 49/s | correct | correct | not proven either way |

Locking more is not simply safer. `POS_LOCK` fixes item merging because two items merge within half a
block, so their locks always overlap — but it does not fix mob pickup, because a mob reaches 1.3 blocks
including diagonals and two mobs can claim one item from further apart than the lock covers. A lock
helps only when its scope matches the interaction's actual reach.

**Recommendation: leave it at `FREE`.** It costs a third of your throughput to fix one of two open
bugs, and that bug is better fixed in code. Raise it only if you would genuinely rather run slowly than
risk item duplication, and understand that even `CHUNK_LOCK` is not a guarantee.

### Naming classes

Entries in any of the three lists are fully-qualified class names or wildcard patterns:

| Entry | Matches |
| --- | --- |
| `com.example.mod.BlockEntityFoo` | that class alone |
| `com.example.mod.*` | every class directly in that package |
| `com.example.mod.**` | that package and everything beneath it |
| `com.example.mod.Foo*` | `Foo`, and nested classes such as `Foo$Ticker` |

A single `*` stops at a package separator but crosses the `$` of a nested class, which is where a
good deal of mod tick code actually lives. Matching is on the class's own name and is **not**
inherited: a subclass in another package is a separate entry.

An entry naming a class this installation does not have is kept as written and ignored, so one
config file can serve a modded and a vanilla instance without losing entries on save.

Vanilla classes that are known not to be safe are handled in code rather than config: pistons,
the sculk blocks and **hoppers** are chunk-locked, and falling blocks, primed TNT and allays run
single-execution. These are not overridable — whitelisting a piston does not make it safe, it makes the world corrupt
quietly.

## Diagnosing problems

`/mcmt stats` reports what is enabled, the pool size, how much is in flight, how much has been
dispatched since startup, and — importantly — anything that has been **auto-demoted**.

Auto-demotion is the safety net: if a tick throws, MCMT records that class as chunk-locked for the
rest of the session rather than letting the exception take the server down. It keeps a server
alive through a thread-safety bug in one mod, but it is a symptom, not a fix. A class listed there
should be added to the relevant blacklist so it is chunk-locked from the start, and the mod should
be told.

`/mcmt perf` reports recent tick times. `/mcmt save`, `/mcmt reload` and `/mcmt restart` persist,
re-read and rebuild respectively.

If the server crashes with MCMT on, the crash report gets an `MCMT` section with the configuration
and, if `opsTracing` was enabled, the tasks that were running.

### Bisecting a failure

1. `/mcmt config disabled true`. If the problem persists, MCMT is not the cause.
2. Otherwise turn the four hooks off one at a time to find the loop.
3. Inside that loop, the question is *race* or *thread identity*. Races come and go with timing;
   thread-identity problems do not. A failure that survives serialising the loop is code checking
   which thread it is on, not two threads colliding.
4. Once you suspect a mod, blacklist all of it at once — `com.example.mod.**` — and confirm the
   problem goes away before narrowing. If chunk-locking the whole mod does not fix it, the tick is
   reaching beyond its own position and wants `singleThreadList` instead; if that does not fix it
   either, the mod is unsafe in a way MCMT cannot contain and the hook has to stay off.
5. Narrow back the other way with the whitelist, a package at a time, to recover throughput.

## Hoppers are slow under MCMT, on purpose

A hopper's tick reads and writes a container that is not its own. Left to run in parallel, two hoppers
sharing a container both read a slot at n, both take one item, and both write n-1 -- one item consumed,
two delivered. Measured on 4096 hoppers over 24000 ticks, 129024 items became 429867.

So hoppers are serialised against their immediate neighbours — each one locks its own block and the
six around it, which is exactly the set it can reach. Two hoppers three blocks apart still run at the
same time; two that share a container never do.

That is as fine-grained as the lock can safely be, and a dense hopper array is still slow, because
**adjacent hoppers genuinely depend on each other**. A row of hoppers passing items along is a chain
of shared containers, so no amount of lock tuning makes it parallel — the serialisation is in the
build, not in MCMT. On top of that a single hopper tick is only about a fifth of a microsecond, which
is less than it costs to hand a task to a worker and take the locks.

Measured on 4096 hoppers packed into 16 chunks:

| | tick rate | ms/tick |
| --- | --- | --- |
| MCMT off | 948–1058/s | ~1.0 |
| MCMT on | 165/s | 6.0 |

**If your tick time is dominated by hopper arrays, MCMT will make it worse, not better.** Turn it off,
or accept the cost. This is a correctness floor, not a tuning knob — the whitelist deliberately cannot
override it, because the alternative is a server that mints items.

## What you are trading away

**Determinism.** The order in which entities and block entities tick within a tick is no longer
fixed. Anything that depends on that order — hopper chains competing for one item, two mobs racing
for the same block, item sorters, some redstone timings — can resolve differently than it would
single-threaded. The same world, same seed and same inputs will not necessarily produce the same
result twice. If you run a world where that matters, do not enable this.

**A larger surface for mod bugs.** Mod tick code has never had to be thread-safe. `chunkLockModded`
defaults to on for exactly this reason, but chunk locking bounds a tick's *position*, not its
reach: a mod that mutates global state from a tick is still unsafe, and MCMT cannot know that.

**Not a guaranteed speed-up.** Parallelism costs dispatch and synchronisation per task. A server
whose tick is dominated by one expensive thing, or by chunk generation rather than ticking, has
nothing for the pool to overlap and will get slower, not faster — and so will one given too few
workers to cover that overhead, as the table above shows. Measure your own server.

## Notes for mod authors

- `EntityTickEvent` and `BlockEntityTickEvent` may fire on a worker thread. So may anything else
  reached from an entity or block entity tick.
- `MinecraftServer.isSameThread()` returns true on a worker running a tick. This is deliberate:
  a worker running a tick *is* the server thread for the purposes of the checks scattered through
  Minecraft, and the alternative — code silently taking its "not on the server thread" branch —
  is far worse than the alternative it replaces.
- The server profiler is inactive on worker threads. `ActiveProfiler` is not thread-safe, and
  `/perf` would otherwise crash the server the moment it was used with MCMT on.
- To make a class of yours safe, the cheap option is to have it chunk-locked (it is, by default).
  The correct option is to make the tick itself safe and whitelist it.
