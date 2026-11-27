package com.solana.mobilewalletadapter.walletlib.scenario;

import android.security.keystore.KeyGenParameterSpec;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.*;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Test-only software stand-in for the "AndroidKeyStore" JCA provider (Robolectric has none). */
public final class FakeAndroidKeyStore extends Provider {
    static final Map<String, Key> KEYS = new ConcurrentHashMap<>();

    public FakeAndroidKeyStore() {
        super("AndroidKeyStore", 1.0, "test-only in-memory AndroidKeyStore");
        put("KeyStore.AndroidKeyStore", Store.class.getName());
        put("KeyGenerator.AES", AesGen.class.getName());
    }

    public static synchronized void install() {
        if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(new FakeAndroidKeyStore());
    }

    public static final class AesGen extends KeyGeneratorSpi {
        private String alias;
        protected void engineInit(SecureRandom r) {}
        protected void engineInit(AlgorithmParameterSpec p, SecureRandom r) { alias = ((KeyGenParameterSpec) p).getKeystoreAlias(); }
        protected void engineInit(int k, SecureRandom r) {}
        protected SecretKey engineGenerateKey() {
            byte[] k = new byte[32]; new SecureRandom().nextBytes(k);
            SecretKey key = new SecretKeySpec(k, "AES");
            if (alias != null) KEYS.put(alias, key);
            return key;
        }
    }

    public static final class Store extends KeyStoreSpi {
        public Key engineGetKey(String a, char[] p) { return KEYS.get(a); }
        public Certificate[] engineGetCertificateChain(String a) { return null; }
        public Certificate engineGetCertificate(String a) { return null; }
        public Date engineGetCreationDate(String a) { return new Date(); }
        public void engineSetKeyEntry(String a, Key k, char[] p, Certificate[] c) { KEYS.put(a, k); }
        public void engineSetKeyEntry(String a, byte[] k, Certificate[] c) {}
        public void engineSetCertificateEntry(String a, Certificate c) {}
        public void engineDeleteEntry(String a) { KEYS.remove(a); }
        public Enumeration<String> engineAliases() { return Collections.enumeration(KEYS.keySet()); }
        public boolean engineContainsAlias(String a) { return KEYS.containsKey(a); }
        public int engineSize() { return KEYS.size(); }
        public boolean engineIsKeyEntry(String a) { return KEYS.containsKey(a); }
        public boolean engineIsCertificateEntry(String a) { return false; }
        public String engineGetCertificateAlias(Certificate c) { return null; }
        public void engineStore(OutputStream s, char[] p) {}
        public void engineLoad(InputStream s, char[] p) {}
    }
}
