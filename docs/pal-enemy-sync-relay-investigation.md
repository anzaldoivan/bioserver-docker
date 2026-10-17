# PAL enemy-synchronization fix: applicability to BioServer (2026-10-17)

## What PAL actually fixed

PAL Outbreak File #2 speaks SN@P over UDP, unlike the Japanese BioServer's TCP
gameplay transport. In PAL's server-v2, unreliable game-channel op-0x0F packets
were consumed by the beacon path instead of reaching other room members.
The optional SN@P relay (`SNAP_GAME_BEACON_RELAY`) forwards both 1 Hz peer
keepalives and entity-state records over each recipient's unreliable channel,
excluding the sender and preserving game payloads. PAL's analysis reports
peer timeouts and frozen enemies without that traffic.

Evidence (PAL repository):
- https://github.com/Koosjuh/pal-outbreak-2-server/blob/main/analysis/g13-enemy-sync-RE-2026-08-26.md
- https://github.com/Koosjuh/pal-outbreak-2-server/blob/main/server-v2/src/udp/snap-lobby-session.js
- https://github.com/Koosjuh/pal-outbreak-2-server/blob/main/server-v2/src/udp/snap-lobby-sessions.js

## What applies to Japanese BioServer

BioServer File #1 and File #2 already broadcast gameplay payloads byte-for-byte
to *other* authenticated clients with the same `gamenumber` over TCP. No PAL
UDP, sequence stamping, beacon flags, enemy AI simulation, spawn ownership,
or client game-logic translation is required for Japanese clients.

**A different packet-classification bug defeats that existing relay:** gameplay
frames start with an unsigned 8-bit LENGTH. Therefore `0x82` means 130
bytes. In both original `GameServerPacketHandler.java` implementations,
the `case (byte)0x82` control branch unconditionally consumed frames beginning
with `0x82`, even when byte 1 was **not** the `0x02` login direction.

The `ServerStreamBuffer.java` files used only `82 02` to identify the
special, non-length-prefixed GSLOGIN message. A legitimate 130-byte gameplay
frame starting `82 02 ...` could be forwarded as a *partial login control*
instead of buffering and delivering its full length.

**Fix:** a game-login response is distinguished by
`82 02 10 31 00 0a ...` (game-client, tell, GSLOGIN 0x1031,
10-byte session payload), including correct handling of a fragmented
login prefix. Any packet that does not match that known control shape uses the
existing gameplay broadcast path. Unauthenticated senders are ignored.

The fix intentionally leaves the established transport, packet contents,
game-session routing, and exclusion of the sending player unchanged.

## Test-first evidence

PR branch: `fix/gameplay-frame-relay-0x82`, based on `master`, separate from
the draft SQLite migration.

- **RED:** Original `master` delivered 0 recipients for a synthetic
  130-byte gameplay frame starting `82 03`. Both game CI jobs failed.
- **GREEN:** Real `ServerStreamBuffer` + `GameServerPacketHandler` run under
  the same Java 17 harness for File #1 and File #2: **20 assertions each,
  both passing**.
- Test cases: frames `82 03` and `82 02`, split 130-byte TCP frames,
  fragmented `GSLOGIN`, existing short game frame, identical byte
  preservation, same-session target only, no sender echo, sender liveness,
  no unauthenticated forwarding, and empty buffers.
- Game server packet relay tests are **socketless** (real classes and queues,
  no network listening or database). Frame contents are deterministic
  **synthetic data**, not evidence of a captured 130-byte enemy packet.

## What is not demonstrated

This closes a demonstrable dropped-gameplay-frame defect, **not** an
empirical proof of corrected enemy movement on a PS2/PCSX2 rig. It does not
port PAL's `SNAP_GAME_BEACON_RELAY` to the Japanese protocol. The Japanese
client's exact enemy-state packet shapes, frequencies, and downstream
simulation logic remain to be captured and compared.

### Required two-client acceptance check

1. Start a File #1 or File #2 game session with two real clients, both with
   the same game session number. Capture the TCP gameplay port:
   File #1 `8690`, File #2 `8590`.
2. Feed/replay any actual 130-byte length-prefixed packets beginning
   `82 03` or `82 02` and compare the sender-to-server bytes with
   the forwarded server-to-peer bytes. Validate recipient exclusion.
3. During a live two-player scenario record enemy placement, movement,
   attack timing and damage synchronization before/after the fix.
   Distinguish application payload problems from network packet loss.
4. Exercise game start, reconnect, disconnect, 1-player and 2-game
   isolation, plus split/coalesced TCP frames. Game-server login must work.
5. If enemy sync still fails despite complete packet relay, inspect the
   client game-state/ownership path separately; this patch cannot solve
   client-side deterministic-state divergence.

The PR is deliberately separate from the SQLite database migration.
