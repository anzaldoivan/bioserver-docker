package bioserver;

import java.lang.reflect.Field;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.List;

/** Zero-database, actual game-server framing/relay regression, shared by both games. */
public final class GameRelayRegressionTest {
    private static int assertions;

    private static void check(boolean ok, String why) {
        assertions++;
        if (!ok) throw new AssertionError(why);
    }

    private static byte[] frame(int len, int second) {
        byte[] frame = new byte[len];
        frame[0] = (byte) len;
        frame[1] = (byte) second;
        for (int i = 2; i < len; ++i) frame[i] = (byte) ((i * 37 + 11) & 0xff);
        return frame;
    }

    @SuppressWarnings("unchecked")
    private static List<GameServerDataEvent> sent(GameServerPacketHandler handler) throws Exception {
        Field f = GameServerPacketHandler.class.getDeclaredField("queue");
        f.setAccessible(true);
        return (List<GameServerDataEvent>) f.get(handler);
    }

    private static void relay130(int second) throws Exception {
        GameServerPacketHandler handler = new GameServerPacketHandler();
        try (SocketChannel source = SocketChannel.open();
             SocketChannel peer = SocketChannel.open();
             SocketChannel outsider = SocketChannel.open()) {
            Client a = new Client(source, "alice", "12345678");
            Client b = new Client(peer, "bob", "22345678");
            Client c = new Client(outsider, "charlie", "32345678");
            a.gamenumber = 9; b.gamenumber = 9; c.gamenumber = 10;
            a.connalive = false;
            handler.getClients().add(a);
            handler.getClients().add(b);
            handler.getClients().add(c);
            byte[] frame = frame(130, second);
            handler.processData(null, source, frame, frame.length);
            List<GameServerDataEvent> queue = sent(handler);
            check(queue.size() == 1, "0x82 0x" + Integer.toHexString(second)
                  + " 130-byte gameplay frame not relayed to exactly one same-game peer; size=" + queue.size());
            check(queue.get(0).socket == peer, "Wrong recipient or sender echo");
            check(Arrays.equals(queue.get(0).data, frame), "Payload changed in relay");
            check(queue.get(0).data != frame, "Input buffer not copied");
            check(a.connalive, "Sender keepalive not refreshed");
        }
    }

    private static void normalRelay() throws Exception {
        GameServerPacketHandler handler = new GameServerPacketHandler();
        try (SocketChannel source = SocketChannel.open(); SocketChannel peer = SocketChannel.open()) {
            Client a = new Client(source, "alice", "12345678");
            Client b = new Client(peer, "bob", "22345678");
            a.gamenumber = 9; b.gamenumber = 9;
            handler.getClients().add(a); handler.getClients().add(b);
            byte[] frame = frame(68, 8);
            handler.processData(null, source, frame, frame.length);
            check(sent(handler).size() == 1, "Ordinary gameplay relay regressed");
            check(Arrays.equals(frame, sent(handler).get(0).data), "Ordinary relay corrupted bytes");
        }
    }

    private static void fragments(int second) {
        ServerStreamBuffer stream = new ServerStreamBuffer();
        byte[] frame = frame(130, second);
        stream.buf.put(frame, 0, 45);
        byte[] partial = stream.getCompleteGameMessages();
        check(partial == null || partial.length == 0, "Partial 130-byte frame escaped");
        stream.buf.put(frame, 45, 85);
        check(Arrays.equals(frame, stream.getCompleteGameMessages()), "Frame reassembly corrupted");
    }

    private static void splitControl() {
        ServerStreamBuffer stream = new ServerStreamBuffer();
        byte[] login = new Packet(Commands.GSLOGIN, Commands.TELL, Commands.GAMECLIENT,
                                  3, "1234512345".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                                  .getPacketData();
        stream.buf.put(login, 0, 2);
        byte[] partial = stream.getCompleteGameMessages();
        check(partial == null || partial.length == 0, "Incomplete login control emitted");
        stream.buf.put(login, 2, login.length - 2);
        check(Arrays.equals(login, stream.getCompleteGameMessages()), "Login control bytes changed");
    }

    private static void invalidSenderAndEmptyInput() throws Exception {
        GameServerPacketHandler handler = new GameServerPacketHandler();
        try (SocketChannel source = SocketChannel.open(); SocketChannel peer = SocketChannel.open()) {
            Client b = new Client(peer, "bob", "22345678");
            b.gamenumber = 9; handler.getClients().add(b);
            handler.processData(null, source, frame(68, 8), 68);
            check(sent(handler).isEmpty(), "Unauthenticated source was relayed");
            handler.processData(null, source, new byte[0], 0);
            check(sent(handler).isEmpty(), "Empty input produced event");
        }
    }

    public static void main(String[] args) throws Exception {
        normalRelay();
        relay130(3);
        relay130(2);
        fragments(3);
        fragments(2);
        splitControl();
        invalidSenderAndEmptyInput();
        System.out.println("PASS " + ServerMain.GAMEID + ": " + assertions + " assertions");
    }
}
