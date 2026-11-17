package tv.prism;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** QMC2 Map/RC4 and Tencent TEA, adapted from Jixun Wu's MIT-licensed qmc2-crypto/tc_tea.
 * See assets/QMC2_NOTICES.txt. Immutable key state; each byte range can be decoded independently. */
final class Qmc2Cipher {
    private static final int SEGMENT = 5120;
    private static final byte[] SIMPLE_KEY = {0x69, 0x56, 0x46, 0x38, 0x2b, 0x20, 0x15, 0x0b};
    private static final byte[] V2_PREFIX = "QQMusic EncV2,Key:".getBytes(StandardCharsets.US_ASCII);
    private final int[] key;
    private final int[] seed;
    private final long hash;

    private Qmc2Cipher(byte[] bytes) {
        if (bytes.length == 0 || (bytes.length > 300 && bytes.length != 512)) {
            throw new IllegalArgumentException("不支持的 QMC2 密钥类型");
        }
        key = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) key[i] = bytes[i] & 255;
        if (key.length <= 300) { seed = null; hash = 0; return; }
        seed = new int[key.length];
        for (int i = 0; i < seed.length; i++) seed[i] = i & 255;
        int j = 0;
        for (int i = 0; i < seed.length; i++) {
            j = (j + seed[i] + key[i]) % seed.length;
            int value = seed[i]; seed[i] = seed[j]; seed[j] = value;
        }
        long value = 1;
        for (int next : key) {
            if (next == 0) continue;
            long product = (value * next) & 0xffffffffL;
            if (product == 0 || product <= value) break;
            value = product;
        }
        hash = value;
    }

    static Qmc2Cipher fromEkey(String raw) {
        if (raw == null || raw.length() > 8192) throw invalidKey();
        String encoded = raw.trim();
        try {
            return parse(encoded);
        } catch (IllegalArgumentException first) {
            // Some sources prepend transport metadata to the conventional 704-character ekey.
            // Try the complete key first so EncV2 keys are not truncated.
            if (encoded.length() > 704) {
                try { return parse(encoded.substring(encoded.length() - 704)); }
                catch (IllegalArgumentException ignored) { }
            }
            throw invalidKey();
        }
    }

    private static Qmc2Cipher parse(String encoded) {
        byte[] value = decode(encoded);
        if (startsWith(value, V2_PREFIX)) {
            byte[] first = teaDecrypt(Arrays.copyOfRange(value, V2_PREFIX.length, value.length),
                    "386ZJY!@#*$%^&)(".getBytes(StandardCharsets.US_ASCII));
            byte[] second = teaDecrypt(first, "**#!(#$%&^a1cZ,T".getBytes(StandardCharsets.US_ASCII));
            value = decode(new String(second, StandardCharsets.US_ASCII));
            Arrays.fill(first, (byte) 0); Arrays.fill(second, (byte) 0);
        }
        if (value.length < 24) throw invalidKey();
        byte[] teaKey = new byte[16];
        for (int i = 0; i < 8; i++) { teaKey[i * 2] = SIMPLE_KEY[i]; teaKey[i * 2 + 1] = value[i]; }
        byte[] body = teaDecrypt(Arrays.copyOfRange(value, 8, value.length), teaKey);
        byte[] derived = new byte[8 + body.length];
        System.arraycopy(value, 0, derived, 0, 8);
        System.arraycopy(body, 0, derived, 8, body.length);
        try { return new Qmc2Cipher(derived); }
        finally {
            Arrays.fill(teaKey, (byte) 0); Arrays.fill(body, (byte) 0);
            Arrays.fill(value, (byte) 0); Arrays.fill(derived, (byte) 0);
        }
    }

    private static byte[] decode(String value) {
        if (value.isEmpty() || value.length() > 8192 || !value.matches("[A-Za-z0-9+/=\\r\\n]+")) throw invalidKey();
        return Base64.decode(value, Base64.DEFAULT);
    }

    void decrypt(long position, byte[] buffer, int offset, int length) {
        if (position < 0 || position > Long.MAX_VALUE - length || offset < 0 || length < 0 || offset > buffer.length - length) {
            throw new IllegalArgumentException("无效的音频字节范围");
        }
        if (seed == null) {
            for (int i = 0; i < length; i++) {
                long at = position + i;
                if (at > 0x7fff) at %= 0x7fff;
                int index = (int) ((at * at + 71214) % key.length);
                int rotation = (index + 4) & 7;
                buffer[offset + i] ^= (byte) ((key[index] << rotation) | (key[index] >>> rotation));
            }
            return;
        }
        int end = offset + length;
        while (offset < end && position < 128) {
            buffer[offset++] ^= (byte) key[segmentKey(position, key[(int) (position & 511)])];
            position++;
        }
        while (offset < end) {
            long id = position / SEGMENT;
            int within = (int) (position % SEGMENT);
            int count = Math.min(end - offset, SEGMENT - within);
            int discard = within + segmentKey(id, key[(int) (id & 511)]);
            int[] state = seed.clone();
            int j = 0, k = 0;
            for (int i = -discard; i < count; i++) {
                j = (j + 1) & 511;
                k = (k + state[j]) & 511;
                int value = state[j]; state[j] = state[k]; state[k] = value;
                if (i >= 0) buffer[offset + i] ^= (byte) state[(state[j] + state[k]) & 511];
            }
            offset += count; position += count;
        }
    }

    private int segmentKey(long id, int value) {
        // Rust's saturating float-to-u64 conversion yields MAX for a zero divisor.
        if (value == 0) return 511;
        return (int) ((long) Math.floor(hash / (((double) id + 1) * value) * 100)) & 511;
    }

    private static byte[] teaDecrypt(byte[] encrypted, byte[] key) {
        if (key.length != 16 || encrypted.length < 16 || encrypted.length % 8 != 0) throw invalidKey();
        byte[] result = encrypted.clone();
        int[] words = {readInt(key, 0), readInt(key, 4), readInt(key, 8), readInt(key, 12)};
        for (int block = 0; block < result.length; block += 8) {
            if (block > 0) for (int i = 0; i < 8; i++) result[block + i] ^= result[block - 8 + i];
            int y = readInt(result, block), z = readInt(result, block + 4), sum = 0xe3779b90;
            for (int round = 0; round < 16; round++) {
                z -= ((y << 4) + words[2]) ^ (y + sum) ^ ((y >>> 5) + words[3]);
                y -= ((z << 4) + words[0]) ^ (z + sum) ^ ((z >>> 5) + words[1]);
                sum -= 0x9e3779b9;
            }
            writeInt(result, block, y); writeInt(result, block + 4, z);
        }
        for (int i = 8; i < result.length; i++) result[i] ^= encrypted[i - 8];
        int start = 3 + (result[0] & 7), end = result.length - 7;
        if (start > end) throw invalidKey();
        for (int i = end; i < result.length; i++) if (result[i] != 0) throw invalidKey();
        byte[] plain = Arrays.copyOfRange(result, start, end);
        Arrays.fill(result, (byte) 0); Arrays.fill(words, 0);
        return plain;
    }

    private static int readInt(byte[] bytes, int at) {
        return ((bytes[at] & 255) << 24) | ((bytes[at + 1] & 255) << 16) | ((bytes[at + 2] & 255) << 8) | (bytes[at + 3] & 255);
    }

    private static void writeInt(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 24); bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8); bytes[at + 3] = (byte) value;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    private static IllegalArgumentException invalidKey() {
        return new IllegalArgumentException("加密音频密钥无效或当前版本不支持此密钥");
    }
}
