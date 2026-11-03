package io.reliablepush.devices;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;

/** The few DER encodings needed to turn a SEC1 EC private key into PKCS#8 (package-private helper of {@link SigningKeys}). */
final class Der {

    /** OID 1.2.840.10045.3.1.7 (prime256v1 / secp256r1), tag and length included. */
    static final byte[] OID_P256 = {0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x03, 0x01, 0x07};
    /** OID 1.2.840.10045.2.1 (id-ecPublicKey), tag and length included. */
    static final byte[] OID_EC_PUBLIC_KEY = {0x06, 0x07, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x02, 0x01};

    private Der() {}

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] sequence(byte[] content) {
        return tlv(0x30, content);
    }

    static byte[] octetString(byte[] content) {
        return tlv(0x04, content);
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (content.length < 0x80) {
            out.write(content.length);
        } else if (content.length < 0x100) {
            out.write(0x81);
            out.write(content.length);
        } else {
            out.write(0x82);
            out.write(content.length >> 8);
            out.write(content.length & 0xFF);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    /** Minimal strict reader; any malformation throws {@link IllegalArgumentException}. */
    static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        /** Reads a SEQUENCE and returns a reader over its content. */
        Reader sequence() {
            return new Reader(next(0x30));
        }

        BigInteger integer() {
            return new BigInteger(next(0x02));
        }

        byte[] octetString() {
            return next(0x04);
        }

        /** Reads the explicit context tag {@code [n]} (constructed) and returns its whole content (tag, length, value). */
        byte[] contextParameters(int n) {
            return next(0xA0 + n);
        }

        private byte[] next(int tag) {
            if (pos + 2 > data.length || (data[pos] & 0xFF) != tag) {
                throw new IllegalArgumentException();
            }
            pos++;
            int length = data[pos++] & 0xFF;
            if (length >= 0x80) {
                int bytes = length & 0x7F;
                if (bytes < 1 || bytes > 2 || pos + bytes > data.length) {
                    throw new IllegalArgumentException();
                }
                length = 0;
                for (int i = 0; i < bytes; i++) {
                    length = (length << 8) | (data[pos++] & 0xFF);
                }
            }
            if (length < 0 || pos + length > data.length) {
                throw new IllegalArgumentException();
            }
            byte[] value = Arrays.copyOfRange(data, pos, pos + length);
            pos += length;
            return value;
        }
    }
}
