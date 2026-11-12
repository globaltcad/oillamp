package dev.promo;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.gui.desktop.Desktop;

/// A genie's desktop, played by the film: it speaks VNC on a socket, as wayvnc does in a lamp,
/// so the window's own viewer connects to it, asks it for the size of its panel and draws what
/// it sends. What it sends is [DesktopArt], painted anew a dozen times a second at whatever size
/// the viewer asked for.
final class StandInDesktop {

    private static final int OWN_WIDTH = 1280, OWN_HEIGHT = 800;

    private final Path socket;
    private final DesktopArt art;
    private volatile int width = OWN_WIDTH, height = OWN_HEIGHT;

    StandInDesktop(DesktopArt art) throws IOException {
        this.art = art;
        Path directory = Files.createTempDirectory("genies-promo");
        socket = directory.resolve("desktop.sock");
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        socket.toFile().deleteOnExit();
        directory.toFile().deleteOnExit();
        Thread.ofPlatform().daemon().name("stand-in desktop").start(() -> {
            while (true) {
                try {
                    SocketChannel viewer = server.accept();
                    Thread.ofPlatform().daemon().name("stand-in desktop viewer").start(() -> serve(viewer));
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    /// What the window is given as the genie's desktop.
    Desktop desktop() { return new Desktop(socket, OWN_WIDTH, OWN_HEIGHT); }

    private void serve(SocketChannel viewer) {
        try (viewer) {
            DataInputStream in = new DataInputStream(Channels.newInputStream(viewer));
            DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Channels.newOutputStream(viewer), 1 << 16));
            out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            in.readNBytes(12);
            out.write(new byte[] {1, 1});               // one way in: no password
            out.flush();
            in.readUnsignedByte();
            out.writeInt(0);
            out.flush();
            in.readUnsignedByte();
            out.writeShort(width);
            out.writeShort(height);
            out.write(new byte[] {32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0});
            byte[] name = "genie".getBytes(StandardCharsets.UTF_8);
            out.writeInt(name.length);
            out.write(name);
            out.flush();
            boolean asked = false;
            while (true) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0 -> in.skipNBytes(19);
                    case 2 -> { in.skipNBytes(1); in.skipNBytes(4L * in.readUnsignedShort()); }
                    case 3 -> {
                        boolean more = in.readUnsignedByte() == 1;
                        in.skipNBytes(8);
                        // Moving pictures, a dozen a second: nothing here says what changed.
                        if (more) Thread.sleep(80);
                        send(out, asked);
                        asked = false;
                    }
                    case 4 -> in.skipNBytes(7);
                    case 5 -> in.skipNBytes(5);
                    case 6 -> { in.skipNBytes(3); in.skipNBytes(Integer.toUnsignedLong(in.readInt())); }
                    case 251 -> {
                        in.skipNBytes(1);
                        width = in.readUnsignedShort();
                        height = in.readUnsignedShort();
                        int screens = in.readUnsignedByte();
                        in.skipNBytes(1 + 16L * screens);
                        asked = true;
                    }
                    default -> throw new IOException("unknown message " + type);
                }
            }
        } catch (IOException | InterruptedException gone) {
            // The viewer closed its desktop: nothing is left to do for it.
        }
    }

    /// One whole picture, after saying that the desktop took the size it was asked for.
    private void send(DataOutputStream out, boolean sized) throws IOException {
        int w = width, h = height;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        // The desktop has the window's fine pixels; what is on it is as large as on a plain screen.
        double fine = StagedWindow.UI / 1.25;
        g.scale(fine, fine);
        art.paint(g, (int) Math.ceil(w / fine), (int) Math.ceil(h / fine));
        g.dispose();
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        byte[] bytes = new byte[pixels.length * 4];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels);
        out.write(new byte[] {0, 0});
        out.writeShort(2);
        out.writeShort(sized ? 1 : 0);                   // asked by this viewer, or by nobody
        out.writeShort(0);                               // done
        out.writeShort(w);
        out.writeShort(h);
        out.writeInt(-308);                              // the desktop's size, and its one screen
        out.write(new byte[] {1, 0, 0, 0});
        out.writeInt(1);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(w);
        out.writeShort(h);
        out.writeInt(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(w);
        out.writeShort(h);
        out.writeInt(0);                                 // raw pixels
        out.write(bytes);
        out.flush();
    }
}
