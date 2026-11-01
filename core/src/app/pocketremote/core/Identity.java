package app.pocketremote.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * This phone's identity towards the TV: a self-signed certificate with its private key. The TV remembers the
 * certificate once pairing succeeds, so it has to be kept (and never shared).
 */
public final class Identity {
    final PrivateKey key;
    final X509Certificate cert;

    private Identity(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
    }

    public static Identity create(String name) throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        return new Identity(kp.getPrivate(), selfSigned(kp, name));
    }

    public static Identity load(File dir) throws IOException, GeneralSecurityException {
        byte[] k = readAll(new File(dir, "key.pk8"));
        byte[] c = readAll(new File(dir, "cert.der"));
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(k));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(c));
        return new Identity(key, cert);
    }

    public static boolean exists(File dir) {
        return new File(dir, "key.pk8").isFile() && new File(dir, "cert.der").isFile();
    }

    public void save(File dir) throws IOException, CertificateException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        writeAll(new File(dir, "key.pk8"), key.getEncoded());
        writeAll(new File(dir, "cert.der"), cert.getEncoded());
    }

    /** Opens a TLS connection that presents this identity. The TV's own certificate is self-signed. */
    SSLSocket connect(String host, int port, byte[] pinSha256, int timeoutMs) throws IOException {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(new javax.net.ssl.KeyManager[]{new FixedKeyManager(key, cert)},
                    new TrustManager[]{new PinningTrust(pinSha256)}, null);
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            raw.setTcpNoDelay(true);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            s.setSoTimeout(timeoutMs);
            s.startHandshake();
            return s;
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS setup failed: " + e.getMessage(), e);
        }
    }

    public static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** With a pin the TV's certificate must match the one seen while pairing; without one anything is accepted. */
    private static final class PinningTrust implements X509TrustManager {
        private final byte[] pin;

        PinningTrust(byte[] pin) {
            this.pin = pin;
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String type) {}

        @Override public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
            if (pin == null) return;
            if (chain == null || chain.length == 0 || !MessageDigest.isEqual(pin, sha256(chain[0].getEncoded()))) {
                throw new CertificateException("The TV's certificate is not the one it paired with");
            }
        }

        @Override public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static final class FixedKeyManager implements X509KeyManager {
        private final PrivateKey key;
        private final X509Certificate cert;

        FixedKeyManager(PrivateKey key, X509Certificate cert) {
            this.key = key;
            this.cert = cert;
        }

        @Override public String[] getClientAliases(String t, Principal[] i) { return new String[]{"c"}; }
        @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return "c"; }
        @Override public String[] getServerAliases(String t, Principal[] i) { return null; }
        @Override public String chooseServerAlias(String t, Principal[] i, Socket s) { return null; }
        @Override public X509Certificate[] getCertificateChain(String a) { return new X509Certificate[]{cert}; }
        @Override public PrivateKey getPrivateKey(String a) { return key; }
    }

    // ---- a minimal DER writer, enough for one self-signed certificate ---------------------------------

    private static X509Certificate selfSigned(KeyPair kp, String name) throws GeneralSecurityException {
        byte[] sigAlg = seq(oid("1.2.840.113549.1.1.11"), tlv(0x05));
        byte[] subject = seq(set(seq(oid("2.5.4.3"), tlv(0x0C, name.getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(utcTime(new Date(now - 60_000L)), utcTime(new Date(now + 3650L * 24 * 3600 * 1000)));
        byte[] basic = seq(oid("2.5.29.19"), tlv(0x04, seq(tlv(0x01, new byte[]{(byte) 0xFF}), integer(BigInteger.ZERO))));
        byte[] san = seq(oid("2.5.29.17"), tlv(0x04, seq(tlv(0x82, name.getBytes(StandardCharsets.US_ASCII)))));
        byte[] tbs = seq(
                tlv(0xA0, integer(BigInteger.valueOf(2))),
                integer(BigInteger.valueOf(1000)),
                sigAlg, subject, validity, subject,
                kp.getPublic().getEncoded(),
                tlv(0xA3, seq(basic, san)));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] sigBytes = sig.sign();
        byte[] bits = new byte[sigBytes.length + 1];
        System.arraycopy(sigBytes, 0, bits, 1, sigBytes.length);
        byte[] der = seq(tbs, sigAlg, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        byte[] b = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (b.length < 128) {
            out.write(b.length);
        } else if (b.length < 256) {
            out.write(0x81);
            out.write(b.length);
        } else {
            out.write(0x82);
            out.write(b.length >> 8);
            out.write(b.length & 0xFF);
        }
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    private static byte[] seq(byte[]... p) { return tlv(0x30, p); }
    private static byte[] set(byte[]... p) { return tlv(0x31, p); }
    private static byte[] integer(BigInteger v) { return tlv(0x02, v.toByteArray()); }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long v = Long.parseLong(parts[i]);
            byte[] tmp = new byte[10];
            int n = 0;
            tmp[n++] = (byte) (v & 0x7F);
            v >>= 7;
            while (v > 0) {
                tmp[n++] = (byte) ((v & 0x7F) | 0x80);
                v >>= 7;
            }
            while (n > 0) out.write(tmp[--n]);
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] b) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b);
        }
    }
}
