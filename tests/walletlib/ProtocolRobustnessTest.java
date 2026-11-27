package com.solana.mobilewalletadapter.walletlib.scenario;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.core.app.ApplicationProvider;

import com.solana.mobilewalletadapter.common.protocol.SessionProperties;
import com.solana.mobilewalletadapter.walletlib.authorization.AuthIssuerConfig;
import com.solana.mobilewalletadapter.walletlib.protocol.MobileWalletAdapterConfig;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * mwa-gauntlet: protocol robustness of walletlib against a misbehaving dapp.
 *
 * Every test drives the real LocalWebSocketServerScenario over a real localhost WebSocket with a
 * dapp that holds the association key (GauntletDapp) and checks the behaviour the MWA spec and
 * JSON-RPC 2.0 require. These faults only hurt the dapp that sends them.
 *
 *   M3  get_capabilities with params: exactly one response per request id (JSON-RPC 2.0)
 *   M4  malformed sign_messages / authorize params: a response, not silence
 *   M5  signed HELLO_REQ with an invalid public key: the session is terminated
 *   M6  encrypted frame shorter than the header: the session is terminated
 */
@RunWith(RobolectricTestRunner.class)
public class ProtocolRobustnessTest {
    @Rule
    public TestRule rule = new InstantTaskExecutorRule();

    private final List<String> events = new CopyOnWriteArrayList<>();
    private LocalWebSocketServerScenario scenario;

    private static final String AUTHORIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"authorize\"," +
            "\"params\":{\"identity\":{\"name\":\"dapp\",\"uri\":\"https://dapp.example\",\"icon\":\"favicon.ico\"}," +
            "\"chain\":\"solana:mainnet\"}}";

    private static String signTx(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"sign_transactions\",\"params\":{\"payloads\":[\"" +
                Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}) + "\"]}}";
    }

    @After
    public void tearDown() {
        if (scenario != null) scenario.close();
    }

    private int startScenario(GauntletDapp dapp) throws Exception {
        FakeAndroidKeyStore.install();
        final int port = ThreadLocalRandom.current().nextInt(49152, 65535);
        final Context context = ApplicationProvider.getApplicationContext();
        final MobileWalletAdapterConfig config = new MobileWalletAdapterConfig(
                10, 10, new Object[]{"legacy", 0}, 0L,
                new String[]{"solana:signTransactions"});
        final CountDownLatch ready = new CountDownLatch(1);
        scenario = new LocalWebSocketServerScenario(context, config, new AuthIssuerConfig("gauntlet"),
                new Recorder(ready), dapp.associationToken(), port, () -> false,
                List.of(SessionProperties.ProtocolVersion.LEGACY, SessionProperties.ProtocolVersion.V1),
                () -> android.net.Uri.parse("data:image/png;base64,AAAA"));
        scenario.startAsync();
        assertTrue("scenario ready", ready.await(5, TimeUnit.SECONDS));
        return port;
    }

    private static void handshake(GauntletDapp dapp, int port) throws Exception {
        dapp.connect(port);
        dapp.sendRaw(dapp.helloReq());
        dapp.finishHandshake(5000);
    }

    private static JSONObject call(GauntletDapp dapp, String json) throws Exception {
        dapp.sendJson(json);
        final List<String> r = dapp.replies(3000);
        assertEquals("exactly one reply to " + json, 1, r.size());
        return new JSONObject(r.get(0));
    }

    /** Authorize, send one request, and require exactly one error reply; the session must stay usable. */
    private void expectOneErrorReply(String json) throws Exception {
        final GauntletDapp dapp = new GauntletDapp();
        final int port = startScenario(dapp);
        handshake(dapp, port);
        assertTrue(call(dapp, AUTHORIZE).has("result"));
        dapp.sendJson(json);
        final List<String> r = dapp.replies(3000);
        assertEquals("exactly one reply (JSON-RPC 2.0) to " + json + ", got " + r, 1, r.size());
        assertTrue("an error reply to " + json + ", got " + r, new JSONObject(r.get(0)).has("error"));
        assertTrue("session still usable afterwards", call(dapp, signTx(99)).has("result"));
    }

    /**
     * After a fatal message the session must be terminated, the same way walletlib already treats
     * a HELLO_REQ with a bad signature: further messages on that connection get no answer.
     */
    private static void assertTerminatedBeforeHandshake(GauntletDapp dapp, String what) throws Exception {
        Thread.sleep(300);
        dapp.sendRaw(dapp.helloReq());   // a valid HELLO_REQ on the same connection
        final byte[] rsp = dapp.inbox.poll(1500, TimeUnit.MILLISECONDS);
        assertNull(what + ": session should be terminated, but a later valid HELLO_REQ was answered", rsp);
    }

    private static void assertTerminatedAfterHandshake(GauntletDapp dapp, String what) throws Exception {
        Thread.sleep(300);
        dapp.sendJson(signTx(99));        // a valid request on the same connection
        final List<String> r = dapp.replies(1500);
        assertTrue(what + ": session should be terminated, but a later request was answered: " + r, r.isEmpty());
    }

    // ---- control ----
    @Test public void control_wellBehavedDapp() throws Exception {
        final GauntletDapp dapp = new GauntletDapp();
        final int port = startScenario(dapp);
        handshake(dapp, port);
        assertTrue(call(dapp, AUTHORIZE).has("result"));
        assertTrue(call(dapp, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"get_capabilities\",\"params\":{}}").has("result"));
        assertTrue(call(dapp, signTx(3)).has("result"));
    }

    /** Reference: how walletlib already handles a HELLO_REQ whose signature does not verify. */
    @Test public void reference_badSignatureHelloTerminatesSession() throws Exception {
        final GauntletDapp dapp = new GauntletDapp();
        final int port = startScenario(dapp);
        dapp.connect(port);
        final byte[] hello = dapp.helloReq();
        hello[100] ^= 1;
        dapp.sendRaw(hello);
        assertTerminatedBeforeHandshake(dapp, "HELLO_REQ with a bad signature");
    }

    /** Reference: how walletlib already handles an encrypted frame that fails authentication. */
    @Test public void reference_tamperedFrameTerminatesSession() throws Exception {
        final GauntletDapp dapp = new GauntletDapp();
        final int port = startScenario(dapp);
        handshake(dapp, port);
        assertTrue(call(dapp, AUTHORIZE).has("result"));
        final byte[] frame = dapp.encrypt("{}".getBytes("UTF-8"), ++dapp.txSeq);
        frame[frame.length - 1] ^= 1;
        dapp.sendRaw(frame);
        assertTerminatedAfterHandshake(dapp, "tampered encrypted frame");
    }

    // ---- M3 ----
    @Test public void m3_getCapabilitiesWithParamsGetsOneReply() throws Exception {
        final GauntletDapp dapp = new GauntletDapp();
        final int port = startScenario(dapp);
        handshake(dapp, port);
        dapp.sendJson("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"get_capabilities\",\"params\":{\"x\":1}}");
        final List<String> r = dapp.replies(3000);
        assertEquals("exactly one reply per request id, got " + r, 1, r.size());
        assertTrue(r.get(0), new JSONObject(r.get(0)).has("error"));
    }

    // ---- M4 ----
    @Test public void m4a_signMessagesWithoutAddresses() throws Exception {
        expectOneErrorReply("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"sign_messages\",\"params\":{\"payloads\":[\"AQID\"]}}");
    }

    @Test public void m4b_signMessagesWithNullAddress() throws Exception {
        expectOneErrorReply("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"sign_messages\",\"params\":{\"payloads\":[\"AQID\"],\"addresses\":[null]}}");
    }

    @Test public void m4c_authorizeWithInvalidSignInPayload() throws Exception {
        final GauntletDapp dapp = new GauntletDapp();
        final int port = startScenario(dapp);
        handshake(dapp, port);
        dapp.sendJson("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"authorize\",\"params\":{\"identity\":{\"name\":\"d\"},\"chain\":\"solana:mainnet\",\"sign_in_payload\":{\"domain\":\"x.example\",\"nonce\":\"!\",\"issuedAt\":\"not-a-date\"}}}");
        final List<String> r = dapp.replies(3000);
        assertEquals("exactly one reply, got " + r, 1, r.size());
        assertTrue(r.get(0), new JSONObject(r.get(0)).has("error"));
    }

    // ---- M5 ----
    private static byte[] signedHello(GauntletDapp dapp, byte[] point) throws Exception {
        final byte[] sig = dapp.signP1363(point);
        final byte[] hello = Arrays.copyOf(point, 129);
        System.arraycopy(sig, 0, hello, 65, 64);
        return hello;
    }

    @Test public void m5a_signedHelloWithWrongPointPrefix() throws Exception {
        final GauntletDapp dapp = new GauntletDapp();
        final int port = startScenario(dapp);
        dapp.connect(port);
        final byte[] qd = GauntletDapp.x962((ECPublicKey) dapp.ecdh.getPublic());
        qd[0] = 5;
        dapp.sendRaw(signedHello(dapp, qd));
        assertTerminatedBeforeHandshake(dapp, "HELLO_REQ with a 0x05 point prefix");
    }

    @Test public void m5b_signedHelloWithPointOffTheCurve() throws Exception {
        final GauntletDapp dapp = new GauntletDapp();
        final int port = startScenario(dapp);
        dapp.connect(port);
        final byte[] qd = new byte[65];
        qd[0] = 4; qd[32] = 1; qd[64] = 1; // (1, 1) is not on P-256
        dapp.sendRaw(signedHello(dapp, qd));
        assertTerminatedBeforeHandshake(dapp, "HELLO_REQ with an off-curve point");
    }

    // ---- M6 ----
    @Test public void m6_encryptedFrameShorterThanHeader() throws Exception {
        final GauntletDapp dapp = new GauntletDapp();
        final int port = startScenario(dapp);
        handshake(dapp, port);
        assertTrue(call(dapp, AUTHORIZE).has("result"));
        dapp.sendRaw(new byte[]{0, 0});
        assertTerminatedAfterHandshake(dapp, "2-byte encrypted frame");
    }

    private class Recorder implements LocalScenario.Callbacks {
        private final CountDownLatch ready;
        Recorder(CountDownLatch ready) { this.ready = ready; }
        public void onScenarioReady() { events.add("onScenarioReady"); ready.countDown(); }
        public void onScenarioServingClients() { events.add("onScenarioServingClients"); }
        public void onScenarioServingComplete() { events.add("onScenarioServingComplete"); }
        public void onScenarioComplete() { events.add("onScenarioComplete"); }
        public void onScenarioError() { events.add("onScenarioError"); }
        public void onScenarioTeardownComplete() { events.add("onScenarioTeardownComplete"); }
        public void onAuthorizeRequest(AuthorizeRequest request) {
            final byte[] pk = new byte[32]; pk[0] = 7;
            request.completeWithAuthorize(new AuthorizedAccount[]{
                    new AuthorizedAccount(pk, "acct", null, null, null)}, null, null, null);
        }
        public void onReauthorizeRequest(ReauthorizeRequest request) { request.completeWithReauthorize(); }
        public void onSignTransactionsRequest(SignTransactionsRequest request) {
            request.completeWithSignedPayloads(request.getPayloads());
        }
        public void onSignMessagesRequest(SignMessagesRequest request) {
            request.completeWithSignedPayloads(request.getPayloads());
        }
        public void onSignAndSendTransactionsRequest(SignAndSendTransactionsRequest request) { request.completeWithDecline(); }
        public void onDeauthorizedEvent(DeauthorizedEvent event) { event.complete(); }
        public void onLowPowerAndNoConnection() { events.add("onLowPowerAndNoConnection"); }
    }
}
