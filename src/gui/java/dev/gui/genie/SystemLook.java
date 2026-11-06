package dev.gui.genie;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import dev.gui.model.Lang;
import dev.gui.model.Palette;

/// Whether the desktop Genies starts on is dark or light, from a handful of clues, so that the
/// first window comes in the palette that fits in: the light one on a light desktop, the dark
/// one on a dark desktop. Only until the user picks a palette; from then on, Genies keeps theirs.
///
/// The clues, the first that says either way deciding:
///
/// 1. `GTK_THEME`, a theme forced on the GTK programs started from the same place, dark when
///    its name says so, as in `Adwaita:dark`;
/// 2. the colour scheme GNOME offers programs, Ubuntu's Dark and Light style among them:
///    `prefer-dark` or `prefer-light`; `default` says nothing, as some desktops leave it so;
/// 3. the GTK theme of GNOME and the desktops built on it, dark when its name says so, as
///    `Yaru-dark` does, and light otherwise;
/// 4. KDE's colour scheme, in `~/.config/kdeglobals`: the one named, dark when its name says so,
///    as `BreezeDark` does, or else the background of its windows;
/// 5. macOS, whose dark appearance is the only one with a name;
/// 6. Windows, which says whether its applications are light.
///
/// None of them, as on a computer without a desktop of these kinds, says nothing.
///
/// It also says which language the desktop speaks, so that the first window speaks it too, if
/// Genies does; again only until the user picks one. On Linux the locale variables say it; on
/// Windows and macOS, where a program started from the Start menu or the Finder has none of
/// them, the language the computer was set up in, as Java was told it, does.
public final class SystemLook {

    private final Function<String, Optional<String>> environment;
    private final Function<List<String>, String> commands;
    private final Optional<Path> home;
    private final Optional<Locale> computer;

    /// The look the clues give, the language from the environment alone.
    ///
    /// @param environment the environment Genies was started from, by variable
    /// @param commands    what a command prints, or nothing when it is not there or fails
    /// @param home        the user's home, with the files desktops keep their settings in; none to
    ///                    read no files
    public SystemLook(Function<String, Optional<String>> environment, Function<List<String>, String> commands, Optional<Path> home) {
        this(environment, commands, home, Optional.empty());
    }

    /// The look the clues give.
    ///
    /// @param computer the language the computer was set up in, for when the environment names
    ///                 none, as on Windows and macOS; none to go by the environment alone
    public SystemLook(
        Function<String, Optional<String>> environment,
        Function<List<String>, String> commands,
        Optional<Path> home,
        Optional<Locale> computer
    ) {
        this.environment = environment;
        this.commands = commands;
        this.home = home;
        this.computer = computer;
    }

    /// The look of the computer Genies runs on, started from `environment`.
    public static SystemLook ofThisComputer(Function<String, Optional<String>> environment) {
        return new SystemLook(environment, SystemLook::output, Optional.of(Path.of(System.getProperty("user.home"))),
                              Optional.of(Locale.getDefault(Locale.Category.DISPLAY)));
    }

    /// The look `environment` alone gives, without asking the computer anything: for Genies on
    /// a simulated machine.
    public static SystemLook ofEnvironment(Function<String, Optional<String>> environment) {
        return new SystemLook(environment, _ -> "", Optional.empty());
    }

    /// The palette that fits the desktop, if any clue says whether it is dark.
    public Optional<Palette> palette() {
        return Stream.<Supplier<Optional<Boolean>>>of(
                this::gtkTheme,
                this::gnomeColourScheme,
                this::gnomeTheme,
                this::kdeColourScheme,
                this::macAppearance,
                this::windowsApps
            )
            .map(Supplier::get)
            .flatMap(Optional::stream)
            .findFirst()
            .map(dark -> dark ? Palette.DARK : Palette.LIGHT);
    }

    /// The language the desktop speaks, if Genies speaks it, as a program's messages are chosen:
    ///
    /// 1. the locale its programs are started with, such as `de_DE.UTF-8`, which `LC_ALL`,
    ///    `LC_MESSAGES` and `LANG` name, the first set deciding;
    /// 2. but before it, unless that locale is the plain `C` or `POSIX`, the first language of
    ///    `LANGUAGE`, GNU's list of them in order, such as `es:en`, that Genies speaks;
    /// 3. and when no variable names a locale, the language the computer was set up in.
    public Optional<Lang> lang() {
        Optional<String> locale = Stream.of("LC_ALL", "LC_MESSAGES", "LANG")
            .map(environment)
            .flatMap(Optional::stream)
            .filter(it -> !it.isBlank())
            .findFirst();
        boolean plain = locale.map(it -> it.equals("C") || it.startsWith("C.") || it.equals("POSIX")).orElse(false);
        Optional<Lang> preferred = plain ? Optional.empty() : environment.apply("LANGUAGE").stream()
            .flatMap(it -> Stream.of(it.split(":")))
            .filter(it -> !it.isBlank())
            .map(SystemLook::spoken)
            .flatMap(Optional::stream)
            .findFirst();
        if (preferred.isPresent()) return preferred;
        if (locale.isPresent()) return locale.flatMap(SystemLook::spoken);
        return computer.flatMap(it -> spoken(it.getLanguage()));
    }

    /// The language `locale` names by its first part, such as `de` in `de_DE.UTF-8`, if Genies
    /// speaks it.
    private static Optional<Lang> spoken(String locale) {
        return switch (locale.split("[_.@-]", 2)[0].toLowerCase(Locale.ROOT)) {
            case "en" -> Optional.of(Lang.ENGLISH);
            case "de" -> Optional.of(Lang.GERMAN);
            case "es" -> Optional.of(Lang.SPANISH);
            case "ja" -> Optional.of(Lang.JAPANESE);
            case "zh" -> Optional.of(Lang.CHINESE);
            default -> Optional.empty();
        };
    }

    private Optional<Boolean> gtkTheme() {
        return environment.apply("GTK_THEME").filter(it -> !it.isBlank()).map(SystemLook::namesDark);
    }

    private Optional<Boolean> gnomeColourScheme() {
        String scheme = commands.apply(List.of("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"));
        if (scheme.contains("prefer-dark")) return Optional.of(true);
        if (scheme.contains("prefer-light")) return Optional.of(false);
        return Optional.empty();
    }

    private Optional<Boolean> gnomeTheme() {
        String theme = commands.apply(List.of("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")).strip();
        return theme.isEmpty() ? Optional.empty() : Optional.of(namesDark(theme));
    }

    private Optional<Boolean> kdeColourScheme() {
        Optional<String> settings = home.map(it -> it.resolve(".config").resolve("kdeglobals")).flatMap(SystemLook::read);
        if (settings.isEmpty()) return Optional.empty();
        String section = "";
        Optional<Boolean> background = Optional.empty();
        for (String line : settings.get().lines().map(String::strip).toList()) {
            if (line.startsWith("[")) { section = line; continue; }
            if (section.equals("[General]") && line.startsWith("ColorScheme="))
                return Optional.of(namesDark(line.substring("ColorScheme=".length())));
            if (section.equals("[Colors:Window]") && line.startsWith("BackgroundNormal="))
                background = darkRgb(line.substring("BackgroundNormal=".length()));
        }
        return background;
    }

    private Optional<Boolean> macAppearance() {
        if (!os().contains("mac")) return Optional.empty();
        // Only the dark appearance has a name: in the light, the setting is not there at all.
        return Optional.of(commands.apply(List.of("defaults", "read", "-g", "AppleInterfaceStyle")).strip().equalsIgnoreCase("Dark"));
    }

    private Optional<Boolean> windowsApps() {
        if (!os().contains("win")) return Optional.empty();
        String answer = commands.apply(List.of(
            "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme"
        ));
        if (answer.contains("0x0")) return Optional.of(true);
        if (answer.contains("0x1")) return Optional.of(false);
        return Optional.empty();
    }

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    /// Whether a theme's or scheme's `name` says it is dark.
    private static boolean namesDark(String name) {
        return name.toLowerCase(Locale.ROOT).contains("dark");
    }

    /// Whether `rgb`, such as `49,54,59`, is a dark colour, if it is one.
    private static Optional<Boolean> darkRgb(String rgb) {
        String[] parts = rgb.split(",", -1);
        if (parts.length < 3) return Optional.empty();
        try {
            int r = Integer.parseInt(parts[0].strip());
            int g = Integer.parseInt(parts[1].strip());
            int b = Integer.parseInt(parts[2].strip());
            return Optional.of(r * 299 + g * 587 + b * 114 < 128_000);
        } catch (NumberFormatException notAColour) {
            return Optional.empty();
        }
    }

    private static Optional<String> read(Path file) {
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file)) : Optional.empty();
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    /// What `command` printed, or nothing when it is not there, fails, or takes more than a
    /// moment, as `gsettings` may without a desktop session to ask.
    private static String output(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                return "";
            }
            return process.exitValue() == 0 ? new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8) : "";
        } catch (IOException notThere) {
            return "";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
