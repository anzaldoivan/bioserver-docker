package bioserver;

/**
 * Distinguish length-prefixed gameplay from the old GSLOGIN control frame.
 *
 * In gameplay the FIRST byte is an unsigned length, so 0x82 is 130 bytes,
 * not necessarily the GAMECLIENT identifier. Never route on byte zero alone.
 */
final class GameServerFrame {
    private static final int SESSION_PAYLOAD_BYTES = 10;
    private static final int[] LOGIN_PREFIX = {0x82, 0x02, 0x10, 0x31};

    private GameServerFrame() {}

    /**
     * Can the bytes received SO FAR still be a game-login response?
     * Partial matching prefixes are held until they can be distinguished
     * safely from length-prefixed gameplay. No session state is inspected.
     */
    static boolean mightBeLogin(byte[] bytes, int offset, int available) {
        if (bytes == null || offset < 0 || available <= 0
                || offset > bytes.length || available > bytes.length - offset) {
            return false;
        }
        for (int i = 0; i < Math.min(available, LOGIN_PREFIX.length); ++i) {
            if ((bytes[offset + i] & 0xff) != LOGIN_PREFIX[i]) return false;
        }
        if (available >= 6) {
            int payloadSize = ((bytes[offset + 4] & 0xff) << 8)
                    | (bytes[offset + 5] & 0xff);
            return payloadSize == SESSION_PAYLOAD_BYTES;
        }
        return true;
    }

    static boolean isCompleteLogin(byte[] bytes, int offset, int available) {
        return available >= Packet.HEADERSIZE + SESSION_PAYLOAD_BYTES
                && mightBeLogin(bytes, offset, available);
    }
}
