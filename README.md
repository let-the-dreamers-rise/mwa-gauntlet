# mwa-gauntlet

Hostile-peer tests for [Mobile Wallet Adapter](https://github.com/solana-mobile/mobile-wallet-adapter) (MWA), the protocol every Android dapp uses to reach every Android wallet, including Seeker's.

The wallet side of MWA is one shared library, `walletlib`. A bug there is a bug in every wallet built on it. walletlib's own unit tests cover low-power timers, Nostr URIs and Nostr crypto. None of them drives a real session against a peer that breaks the protocol.

mwa-gauntlet does. Its tests run **inside walletlib's own Robolectric suite**, against the real `LocalWebSocketServerScenario`, over a real localhost WebSocket, with the real auth repository. The dapp side (`GauntletDapp`) is a from-scratch MWA dapp endpoint that follows the spec by default and can break it at any step: HELLO_REQ, key agreement, framing, sequence numbers, JSON-RPC. A test-only software `AndroidKeyStore` (`FakeAndroidKeyStore`) lets the scenario run under Robolectric, which has none.

## Results (MWA main `2695142`, 9 Nov 2026)

| Test | Spec / JSON-RPC rule | Main | With `patches/public-fixes.patch` |
|---|---|---|---|
| `control_wellBehavedDapp` | | pass | pass |
| `reference_badSignatureHelloTerminatesSession` | existing behaviour | pass | pass |
| `reference_tamperedFrameTerminatesSession` | existing behaviour | pass | pass |
| `m3_getCapabilitiesWithParamsGetsOneReply` | one response per request id | **2 responses** | pass |
| `m4a_signMessagesWithoutAddresses` | every request gets a response | **no response** | pass |
| `m4b_signMessagesWithNullAddress` | every request gets a response | **no response** | pass |
| `m4c_authorizeWithInvalidSignInPayload` | every request gets a response | **no response** | pass |
| `m5a_signedHelloWithWrongPointPrefix` | invalid HELLO_REQ ends the session | **session left open** | pass |
| `m5b_signedHelloWithPointOffTheCurve` | invalid HELLO_REQ ends the session | **session left open** | pass |
| `m6_encryptedFrameShorterThanHeader` | invalid frame ends the session | **frame ignored** | pass |

With the patch, the full existing `common`, `walletlib` and `clientlib` unit suites still pass. CI checks all of this on every push and weekly. It also re-runs the tests against upstream `main`, to show which findings still reproduce.

Details, causes and fixes are in [FINDINGS.md](FINDINGS.md). These four only affect the dapp that sends the malformed message, so they are public, and each is filed upstream: M3 [#1841](https://github.com/solana-mobile/mobile-wallet-adapter/issues/1841), M4 [#1842](https://github.com/solana-mobile/mobile-wallet-adapter/issues/1842), M5 [#1843](https://github.com/solana-mobile/mobile-wallet-adapter/issues/1843), M6 [#1844](https://github.com/solana-mobile/mobile-wallet-adapter/issues/1844).

**Disclosure note:** four further walletlib findings were reported privately to the MWA maintainers on 27 Nov 2026, with a patch and tests. Their tests are not in this repository and will be added once they are fixed or the maintainers agree to disclosure.

## Run it

Needs JDK 17, git and the Android SDK (`ANDROID_HOME`), the same setup MWA's own Android CI uses.

```
scripts/run.sh pinned         # MWA at the pinned commit: the findings must reproduce
scripts/run.sh patched        # same, with the fixes: everything must pass
scripts/run.sh upstream-main  # MWA main today: which findings still reproduce
```

`run.sh` clones MWA, copies `tests/walletlib/*.java` into `android/walletlib/src/test/java/.../walletlib/scenario/`, optionally applies `patches/public-fixes.patch`, runs Gradle, and checks the JUnit results against [EXPECTED.json](EXPECTED.json). To use the tests in MWA directly, copy the three files into that folder and run `./gradlew :walletlib:testDebugUnitTest --tests '*ProtocolRobustnessTest*'`.

## Layout

```
tests/walletlib/GauntletDapp.java            spec-following dapp endpoint that can break any step
tests/walletlib/FakeAndroidKeyStore.java     test-only AndroidKeyStore provider for Robolectric
tests/walletlib/ProtocolRobustnessTest.java  the public tests
patches/public-fixes.patch                   fixes for M3-M6 (4 files, +41/-4)
scripts/run.sh, scripts/check.py             runner and result check
upstream.env, EXPECTED.json                  pinned commit and expected outcome
```

## Roadmap

1. The rest of the walletlib state machine: reauthorize and auth-token tampering, deauthorize races, request limits, concurrent sessions.
2. The dapp side: `clientlib` and the JS `mobile-wallet-adapter-protocol` against a hostile wallet. Remote transports (reflector and Nostr), where the relay is an adversary.
3. A black-box conformance mode that any wallet team can point at their own wallet app over the local WebSocket, including wallets that don't use walletlib.

MIT licensed. Found and fixed with AI-assisted tooling; every result here comes from running the tests.
