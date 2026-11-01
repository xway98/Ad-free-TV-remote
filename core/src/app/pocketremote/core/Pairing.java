package app.pocketremote.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLSocket;

/**
 * Pairs with a TV on port 6467. {@link #start()} makes the TV show a 6-character code, {@link #finish(String)}
 * proves to the TV that the code was read from its screen. The TV then trusts this identity.
 */
public final class Pairing implements Closeable {
    public static final int PORT = 6467;
    private static final int STATUS_OK = 200;

    private final Identity identity;
    private final String host;
    private final String clientName;
    private SSLSocket socket;
    private InputStream in;
    private OutputStream out;

    public Pairing(Identity identity, String host, String clientName) {
        this.identity = identity;
        this.host = host;
        this.clientName = clientName;
    }

    /** Connects and runs the handshake up to the point where the TV displays its code. */
    public void start() throws IOException {
        socket = identity.connect(host, PORT, null, 8000);
        in = socket.getInputStream();
        out = socket.getOutputStream();

        send(new Pb.Writer().varint(1, 2).varint(2, STATUS_OK)
                .message(10, new Pb.Writer().string(1, "atvremote").string(2, clientName)));
        expect(11, "pairing request");

        Pb.Writer encoding = new Pb.Writer().varint(1, 3).varint(2, 6);
        send(new Pb.Writer().varint(1, 2).varint(2, STATUS_OK)
                .message(20, new Pb.Writer().message(1, encoding).varint(3, 1)));
        expect(20, "options");

        send(new Pb.Writer().varint(1, 2).varint(2, STATUS_OK)
                .message(30, new Pb.Writer().message(1, new Pb.Writer().varint(1, 3).varint(2, 6)).varint(2, 1)));
        expect(31, "configuration");
    }

    /**
     * @return SHA-256 of the TV's certificate, to be remembered and checked on every later connection
     * @throws SecurityException if the code is wrong
     */
    public byte[] finish(String code) throws IOException {
        if (code == null || !code.matches("[0-9a-fA-F]{6}")) throw new SecurityException("The code is 6 characters, 0-9 and A-F");
        try {
            Certificate[] chain = socket.getSession().getPeerCertificates();
            RSAPublicKey server = (RSAPublicKey) chain[0].getPublicKey();
            RSAPublicKey client = (RSAPublicKey) identity.cert.getPublicKey();

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(unsigned(client.getModulus()));
            sha.update(unsigned(client.getPublicExponent()));
            sha.update(unsigned(server.getModulus()));
            sha.update(unsigned(server.getPublicExponent()));
            sha.update(hex(code.substring(2)));
            byte[] digest = sha.digest();
            if ((digest[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) {
                throw new SecurityException("That code doesn't match. Check the TV and try again");
            }

            send(new Pb.Writer().varint(1, 2).varint(2, STATUS_OK).message(40, new Pb.Writer().bytes(1, digest)));
            expect(41, "secret");
            return Identity.sha256(chain[0].getEncoded());
        } catch (java.security.GeneralSecurityException | ClassCastException e) {
            throw new IOException("Pairing failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private void send(Pb.Writer msg) throws IOException {
        Pb.writeFrame(out, msg.toBytes());
    }

    private void expect(int field, String what) throws IOException {
        List<Pb.Field> msg = Pb.parse(Pb.readFrame(in));
        Pb.Field status = Pb.find(msg, 2);
        if (status != null && status.num != STATUS_OK) throw new IOException("The TV refused pairing (" + status.num + ")");
        if (Pb.find(msg, field) == null) throw new IOException("Unexpected reply from the TV while waiting for " + what);
    }

    /** Big-endian bytes without a sign byte, the form the protocol hashes. */
    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        return (b.length > 1 && b[0] == 0) ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
