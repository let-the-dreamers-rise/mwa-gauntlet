# Public findings (MWA main 2695142)

All four only affect the dapp that sends the malformed message. None crashes the wallet: in this version Java-WebSocket catches a `RuntimeException` thrown from `onMessage` and only logs it. Each has a test in `tests/walletlib/ProtocolRobustnessTest.java` and a fix in `patches/public-fixes.patch`.

Duplicate check (25 Nov 2026): issues, pull requests and every non-dependabot branch of solana-mobile/mobile-wallet-adapter. No existing report or fix.

## M3: get_capabilities with params gets two responses

`get_capabilities` with non-empty params gets `-32602 "params expected to be empty"` and then the full capabilities result, both for the same request id. JSON-RPC 2.0 allows exactly one response per request.

Cause: `MobileWalletAdapterServer.handleGetCapabilities` has no `return` after the error.
Fix: add the `return`.

## M4: some malformed requests get no response at all

The dapp waits until its own timeout, then has to guess what happened:

- `sign_messages` without `addresses`
- `sign_messages` with `addresses: [null]`
- `authorize` with a `sign_in_payload` that fails to parse (bad nonce or date)

Cause: `MobileWalletAdapterServer.dispatchRpc` catches only `IOException`. An `IllegalArgumentException` or `JSONException` from parameter parsing escapes, and Java-WebSocket logs and drops it.
Fix: catch `RuntimeException` in `dispatchRpc` and answer `-32602` "params are not valid". The session stays usable, which the tests check.

## M5: a signed HELLO_REQ with an invalid public key leaves the session hanging

A HELLO_REQ correctly signed with the association key, but carrying an invalid P-256 point, gets no HELLO_RSP, no session error and no close. Two variants: a `0x05` prefix, and the point (1, 1), which is not on the curve. A HELLO_REQ with a bad signature, by contrast, ends the session, and the reference test shows this.

Cause: `ECDSAKeys.decodeP256PublicKey` throws `IllegalArgumentException` for a bad prefix, but `parseHelloReq` catches only `UnsupportedOperationException`. For an off-curve point, key agreement fails with `UnsupportedOperationException` outside any handler. Neither becomes a `SessionMessageException`, so the session is never terminated.
Fix: catch both in `parseHelloReq`. Check explicitly that the point is on P-256, with coordinates below p, in `decodeP256PublicKey`. Turn an ECDH failure into a `SessionMessageException`. The on-curve check also protects `clientlib`, which uses the same decoder for HELLO_RSP. Only a dapp holding the association key can send this message, and the JCE provider rejected the off-curve point here. So this is about clean failure, not key leakage.

## M6: a 2-byte encrypted frame is silently ignored

An encrypted frame shorter than the 4-byte sequence number plus IV and tag is ignored, and the session continues. Any other invalid frame, such as one with a bad tag (reference test), ends the session.

Cause: `ByteBuffer.wrap(payload, 0, 4)` throws `IndexOutOfBoundsException` for a shorter payload before any check, and it is not a `SessionMessageException`.
Fix: reject frames shorter than sequence number + IV + tag with a `SessionMessageException`.
