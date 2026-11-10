package dev.promo;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/// The clock the window's animations go by while the film is made. Painting one picture of the
/// film takes longer than the picture is shown, so the window cannot be filmed as it plays:
/// its animations would skip. Instead the film moves this clock on by one picture at a time, and
/// everything that moves in the window, a flame, a wave, an answer fading in, is where it would
/// be at that moment.
///
/// The window asks the computer for the time, not this clock. So the film's JVM is started with
/// this class as its agent (`-javaagent`, which `./gradlew promo` does): as SwingTree's and the
/// window's classes are loaded, every `System.currentTimeMillis()` and `System.nanoTime()` in
/// them is turned into a call of [#millis] and [#nanos].
public final class FilmClock {

    private FilmClock() {}

    /// Whether the window's classes ask this clock: the JVM was started with it as its agent.
    private static volatile boolean attached = false;
    /// The computer's time when the film began, and how far the film is, in nanoseconds; while
    /// no film is made, the computer's own time is told.
    private static final long BEGAN_MILLIS = System.currentTimeMillis(), BEGAN_NANOS = System.nanoTime();
    private static volatile long film = -1;

    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
                if (name == null || !(name.startsWith("swingtree/") || name.startsWith("dev/gui/view/"))) return null;
                return onThisClock(bytes);
            }
        });
        attached = true;
    }

    static boolean attached() { return attached; }

    /// The film is `seconds` in. The film's clock only ever moves on.
    static void at(double seconds) { film = Math.max(film, (long) (seconds * 1e9)); }

    /// How far the film is, in seconds.
    static double seconds() { return Math.max(0, film) / 1e9; }

    public static long millis() { return film < 0 ? System.currentTimeMillis() : BEGAN_MILLIS + film / 1_000_000; }

    public static long nanos() { return film < 0 ? System.nanoTime() : BEGAN_NANOS + film; }

    /// The class in `bytes`, asking this clock wherever it asked the computer for the time.
    private static byte[] onThisClock(byte[] bytes) {
        ClassDesc clock = ClassDesc.of(FilmClock.class.getName());
        MethodTypeDesc time = MethodTypeDesc.of(ConstantDescs.CD_long);
        ClassFile classFile = ClassFile.of();
        boolean[] changed = {false};
        byte[] turned = classFile.transformClass(classFile.parse(bytes), ClassTransform.transformingMethodBodies((code, element) -> {
            if (element instanceof InvokeInstruction call && call.opcode() == Opcode.INVOKESTATIC
                    && call.owner().asInternalName().equals("java/lang/System")
                    && (call.name().equalsString("currentTimeMillis") || call.name().equalsString("nanoTime"))) {
                code.invokestatic(clock, call.name().equalsString("nanoTime") ? "nanos" : "millis", time);
                changed[0] = true;
            } else {
                code.with(element);
            }
        }));
        return changed[0] ? turned : null;
    }
}
