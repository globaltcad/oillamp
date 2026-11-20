package dev.oillamp;

import java.util.regex.Pattern;

/// Text the agent, a model or a program in the sandbox chose, made fit to show a person: the
/// words `lamp show` sends, the command line of a program that listens on a port in the sandbox,
/// the agent's answers and conversations, a checker's reason.
///
/// Such text reaches a terminal and an application's window. An escape sequence or a control
/// character could change how a terminal shows everything after it, a line break could make one
/// line look like two, and a format character such as the right-to-left override could make the
/// text read other than it is. So each of these becomes a space. [#oneLine] also makes each run
/// of spaces one space and cuts the text to a length; [#printable] keeps the spaces as they are,
/// for a line of an answer whose indentation means something.
final class UntrustedTextUtil {

    private UntrustedTextUtil() {}

    /// Escape sequences, control characters (line breaks among them), format characters, and
    /// the line and paragraph separators.
    private static final Pattern NOT_SHOWN =
            Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B.|[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Zs}]+");

    /// `text` with a space for each escape sequence, control, format or separator character, and
    /// otherwise as it was, its spaces and their indentation too.
    static String printable(String text) {
        return NOT_SHOWN.matcher(text).replaceAll(" ");
    }

    /// `text` on one line, with nothing in it but what a person reads, at most `most` characters
    /// long, ending in `…` where it was cut.
    static String oneLine(String text, int most) {
        String line = SPACES.matcher(printable(text)).replaceAll(" ").strip();
        return line.codePointCount(0, line.length()) <= most ? line
                : line.substring(0, line.offsetByCodePoints(0, most - 1)) + "…";
    }
}
