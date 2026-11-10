package dev.promo;

import java.awt.Component;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.Lang;
import dev.gui.model.Palette;
import dev.gui.pi.PiEvent;
import dev.gui.view.PromoArt;

/// The film, scene by scene: what is shown, what is said, and when. Change a word or a moment
/// here and film it again.
final class Storyboard {

    private Storyboard() {}

    static List<Scene> scenes() {
        return List.of(
            Problem.scene(List.of(
                "AI agents are getting hands.",
                "Big Tech is using them to get an even tighter grip on your life…",
                "…by managing your personal life on their data centres.",
                "Your life, on someone else's computer.",
                "What if your agent lived with you instead?",
                "On your machines."
            )),
            wake(), ask(), history(), schedule(), access(), model(), converge(),
            Acts.anything("What will you ask for?", List.of(
                new Acts.Wish(Story.JUNIPER, PromoArt.Pose.WORKING, "an app for\nmy plants"),
                new Acts.Wish(Story.SAMI, PromoArt.Pose.THINKING, "a briefing\nevery morning"),
                new Acts.Wish(Story.REX, PromoArt.Pose.WORKING, "my invoices,\nsorted"),
                new Acts.Wish(Story.FOURTH, PromoArt.Pose.WAVING, "a website\nfor the band"),
                new Acts.Wish(Story.FIFTH, PromoArt.Pose.THINKING, "a game\nfor the kids")
            ), "You describe it. A genie builds it."),
            Acts.end(Story.JUNIPER, "Genies", "Your agents. Your machine. Your rules.", List.of(
                "Runs on Linux, in sandboxes made by oillamp",
                "Works with open models on your own hardware",
                "Free and open source, MIT licensed"
            ), "github.com/globaltcad/oillamp")
        );
    }

    /// The point of light the question left bursts into the window's first button. The user
    /// presses it: the button fades, the bar it sat on shows, and the camera draws back to a lamp
    /// being lit, from whose flame a genie takes form, before any window is there. Then the
    /// window comes together around the two of them, seen at an angle, part by part.
    private static Scene wake() {
        return new AppScene("wake", 22.5) {
            @Override GeniesState start() { return Story.genies(); }

            /// What is said under the lamp's picture, and the bar under that, which first has
            /// the button, then says how the waking goes, and then takes the user's messages.
            private Optional<Component> picture() { return window.labelSaying("Juniper is ").map(it -> StagedWindow.above(it, 1)); }
            private Optional<Component> bar() {
                return window.buttonSaying("Wake").map(it -> StagedWindow.above(it, 1))
                    .or(() -> window.labelSaying("Waking Juniper").map(it -> StagedWindow.above(it, 1)))
                    .or(() -> window.all(javax.swing.JTextArea.class).stream().findFirst().map(it -> StagedWindow.above(it, 3)));
            }
            private Optional<Component> card(int which) {
                List<Component> cards = window.all(Component.class).stream().filter(it -> it.getClass().getSimpleName().equals("EntryPanel")).toList();
                return which < cards.size() ? Optional.of(cards.get(which)) : Optional.empty();
            }
            private Optional<Component> inTheHeader(String words) {
                return window.buttonSaying(words).filter(it -> window.unitsOf(it).getY() < 90).map(it -> it);
            }

            /// Where the button is, and was: the window no longer has it once it was pressed, and
            /// the camera draws back from where it was.
            private Rectangle2D button = new Rectangle2D.Double(0, 0, 1, 1);

            /// Near the button, so that it is where the point of light was.
            private Camera onTheButton() {
                window.buttonSaying("Wake").map(window::unitsOf).ifPresent(it -> button = it);
                return new Camera(button.getCenterX(), button.getCenterY(), 4.4).with(Effects.SEED.x, Effects.SEED.y);
            }

            /// The lamp, what is said under it, and the bar: all there is while the lamp is alone.
            private Camera onTheLamp() {
                Optional<Rectangle2D> said = window.labelSaying("Juniper is ").map(window::unitsOf), under = bar().map(window::unitsOf);
                if (said.isEmpty() || under.isEmpty()) return whole();
                Rectangle2D all = new Rectangle2D.Double(under.get().getX(), said.get().getY() - 128, under.get().getWidth(), under.get().getMaxY() - said.get().getY() + 128);
                return near(() -> Optional.of(all), 12, 3.0).get();
            }

            /// The list of genies, and then the header, as the camera looks at each being put together.
            private Camera onTheList() { return new Camera(250, 330, 1.75).with(MIDDLE_X - 330, MIDDLE_Y + 50); }
            private Camera onTheHeader() { return new Camera(820, 250, 1.7).with(MIDDLE_X + 60, MIDDLE_Y - 150); }

            {
                cutsOut();
                looks(0, 0, this::onTheButton);
                holds(() -> window.buttonSaying("Wake").map(it -> it), t -> Pose.popping(t, 0.12, 0.85));
                press(2.0, "Wake");
                pointerLeaves(2.5);

                // Pressed, the button fades where it was, the bar it sat on shows around it, and
                // the camera draws back from it to the lamp, in one go.
                fades(2.12, 0.55, () -> window.buttonSaying("Wake").map(it -> it));
                holds(this::bar, t -> t < 9.6 ? Pose.fading(t, 2.05, 2.7) : Pose.LANDED);
                holds(this::picture, t -> t < 9.6 ? Pose.fading(t, 2.9, 3.9) : Pose.LANDED);
                looksGently(5.2, 3.1, this::onTheLamp);
                at(3.3, () -> window.genie(it -> it.lampSays(new Genie.Activity.CheckingThisComputer())));
                at(4.3, () -> window.genie(it -> it.lampSays(new Genie.Activity.StartingTheSandbox())));
                at(5.4, () -> window.genie(it -> it.lampSays(new Genie.Activity.WakingTheGenie())));
                at(6.2, () -> window.genie(Genie::awake));
                says(8.2, 11.4, "Genies", "Your own AI agents, at home on your Linux machine.");

                // The window comes together around the genie: first its ground, then, as the
                // camera goes along it at an angle, what is on it, one thing after the other.
                looks(11.0, 1.9, this::whole);
                says(11.8, 16.5, "Every genie lives in a lamp", "A whole Linux computer of its own, sealed off from yours.", Side.TOP_RIGHT, 0);
                looks(13.0, 1.5, this::onTheList);
                double lands = 12.1;
                holds(() -> window.buttonSaying("New genie").map(it -> it), t -> Pose.dropping(t, lands));
                holds(() -> window.labelSaying("YOUR GENIES"), t -> Pose.dropping(t, lands + 0.15));
                for (int card = 0; card < 3; card++) {
                    int which = card;
                    holds(() -> card(which), t -> Pose.dropping(t, lands + 0.4 + which * 0.28));
                }
                holds(() -> window.buttonSaying("Add a lamp").map(it -> it), t -> Pose.dropping(t, lands + 1.4));
                holds(() -> window.buttonSaying("Settings").map(it -> it), t -> Pose.dropping(t, lands + 1.55));
                pointsOut(14.3, 16.5, () -> card(0).map(window::unitsOf).flatMap(first -> card(2).map(window::unitsOf).map(first::createUnion)));

                says(16.9, 22.2, "With everything it needs", "A desktop to work on, a schedule, a history, and rules for what it may touch.", Side.BOTTOM_LEFT, 0);
                looks(17.9, 1.5, this::onTheHeader);
                double header = 17.2;
                holds(() -> window.component(it -> it instanceof javax.swing.JLabel label && "Juniper".equals(label.getText()) && window.unitsOf(it).getX() > 255).map(it -> StagedWindow.above(it, 1)), t -> Pose.dropping(t, header));
                List<String> buttons = List.of("Desktop", "Sleep", "Chat", "Actions", "Schedule", "History", "Access");
                for (int button = 0; button < buttons.size(); button++) {
                    int which = button;
                    holds(() -> inTheHeader(buttons.get(which)), t -> Pose.dropping(t, header + 0.3 + which * 0.2));
                }
                looks(22.3, 1.7, this::whole);
            }

            /// While the lamp is alone, the window is small, so that the bar under the lamp is
            /// as wide as a message box and no wider; it grows to its size before it shows.
            @Override void paint(java.awt.Graphics2D g, double t) {
                double grown = Draw.ease(t, 8.4, 9.6);
                window.size(Draw.mix(700, WIDE, grown), Draw.mix(540, TALL, grown));
                super.paint(g, t);
            }

            @Override double windowShown(double t) { return Draw.ease(t, 9.8, 10.9); }

            /// As the window comes out of the night around the genie, the dusk and the sand grow out
            /// from behind it, swirling, as if it were forged from them.
            @Override double lit(double t) { return t < 10.9 ? 0 : 1; }
            @Override double drawnIn(double t) { return 1 - Draw.ease(t, 11.0, 13.4); }
            @Override java.awt.geom.Point2D drawnInto() { return new java.awt.geom.Point2D.Double(MIDDLE_X, MIDDLE_Y); }

            /// As the window shows, it lies back, its bottom edge towards the viewer. For the
            /// list of genies its left side comes forward, where they are put down; for the
            /// header its top and its right, where the buttons are. Then it stands up straight.
            @Override Lens.Tilt tilt(double t) {
                Lens.Tilt back = new Lens.Tilt(0.56, -0.06, 0.85), left = new Lens.Tilt(0.2, 0.56, 0.8), topRight = new Lens.Tilt(-0.4, -0.46, 0.8);
                return Lens.Tilt.NONE
                    .toward(back, Draw.ease(t, 9.6, 11.2))
                    .toward(left, Draw.ease(t, 11.7, 13.2))
                    .toward(topRight, Draw.ease(t, 16.5, 18.0))
                    .toward(Lens.Tilt.NONE, Draw.ease(t, 20.6, 22.1));
            }

            @Override void above(java.awt.Graphics2D g, double t) {
                Effects.light(g, Effects.SEED, 1 - Draw.ease(t, 0.1, 0.5));
                Effects.burst(g, Effects.SEED, t - 0.12, 330);
            }
        };
    }

    /// A place in the window by the words of a label in it: from `dx`, `dy` units off the
    /// label's corner, `width` by `height` units.
    private static Supplier<Optional<Rectangle2D>> by(AppScene scene, String words, double dx, double dy, double width, double height) {
        return () -> scene.window.labelSaying(words).map(scene.window::unitsOf)
            .map(it -> new Rectangle2D.Double(it.getX() + dx, it.getY() + dy, width, height));
    }

    /// Juniper's history: every step saved, and a branch beside it.
    private static Scene history() {
        return new AppScene("history", 11.5) {
            @Override GeniesState start() { return Story.afterTheApp(); }
            {
                cutsIn(); cutsOut(); afterTheApp();
                press(1.0, "History");
                says(1.6, 11.1, "Every step is saved", "Don't like where it went? Go back in time. Or branch off and keep both.", Side.BOTTOM_RIGHT, 1.5);
                looks(4.4, 1.2, near(by(this, "MOMENTS", -20, -14, 600, 430), 0, 2.2, roomBeside(Side.BOTTOM_RIGHT)));
                point(6.2, 0.8, by(this, "19:40", 72, -2, 170, 18));
                at(6.35, () -> window.genie(it -> it.withHistory(it.history().pick(Story.id("9f")))));
                pointsOut(6.7, 8.9, () -> window.buttonSaying("Go back to this moment").map(it -> window.unitsOf(StagedWindow.above(it, 2))));
                pointerLeaves(7.8);
                looks(11.3, 1.2, this::whole);
            }
        };
    }

    /// Sami's week: the jobs that wake her.
    private static Scene schedule() {
        return new AppScene("schedule", 11.5) {
            @Override GeniesState start() { return Story.afterTheApp().withPage(GeniesState.Page.HISTORY); }
            {
                cutsIn(); cutsOut(); afterTheApp();
                pressCard(0.8, "Sami", () -> window.change(it -> it.select(Story.SAMI)));
                press(1.8, "Schedule");
                pointerLeaves(2.4);
                says(2.4, 11.1, "Give it a job", "Every weekday at eight, a briefing is waiting. You were still asleep.", Side.BOTTOM_LEFT, 1.5);
                looks(5.2, 1.2, near(by(this, "JOBS", -24, -16, 440, 400), 0, 2.2, roomBeside(Side.BOTTOM_LEFT)));
                pointsOut(5.4, 7.6, () -> window.labelSaying("next: tomorrow").map(it -> window.unitsOf(StagedWindow.above(it, 2))));
                looks(8.9, 1.3, near(by(this, "THE WEEK", -20, 190, 660, 400), 0, 2.0, roomBeside(Side.BOTTOM_LEFT)));
                looks(11.3, 1.2, this::whole);
            }
        };
    }

    /// What Rex may reach of this computer: only what the user wrote down.
    private static Scene access() {
        return new AppScene("access", 11.5) {
            @Override GeniesState start() { return Story.afterTheApp().select(Story.SAMI).withPage(GeniesState.Page.SCHEDULE); }

            /// The page's fields the user writes rules into, by their order: the folders it may
            /// read, those it may change, and anything else.
            private Optional<Rectangle2D> field(int which) {
                return window.all(javax.swing.JTextArea.class).stream().filter(it -> it.isEditable() && it.getHeight() > 100).skip(which).findFirst().map(window::unitsOf);
            }
            {
                cutsIn(); cutsOut(); afterTheApp();
                pressCard(0.8, "Rex", () -> window.change(it -> it.select(Story.REX)));
                press(1.8, "Access");
                at(2.0, this::toTheTop);
                at(2.2, this::toTheTop);
                pointerLeaves(2.4);
                says(2.4, 11.1, "Your computer stays yours", "A genie sees nothing of it. Unless you open a door, folder by folder.", Side.TOP_RIGHT, 1.5);
                looks(5.2, 1.3, near(() -> field(0).map(it -> new Rectangle2D.Double(it.getX() - 16, it.getY() - 100, 964, 440)), 0, 1.9, roomBeside(Side.TOP_RIGHT)));
                pointsOut(5.5, 7.7, () -> field(0));
                pointsOut(7.9, 10.1, () -> field(2).map(it -> new Rectangle2D.Double(it.getX(), it.getY(), it.getWidth(), 60)));
                looks(11.3, 1.2, this::whole);
            }
        };
    }

    /// The settings: the model runs on this computer too.
    private static Scene model() {
        return new AppScene("model", 10.5) {
            @Override GeniesState start() { return Story.afterTheApp().select(Story.REX).withPage(GeniesState.Page.HOST_ACCESS); }
            {
                cutsIn(); cutsOut(); afterTheApp();
                press(1.0, "Settings");
                pointerLeaves(1.6);
                says(1.6, 10.1, "The model is yours, too", "An open model on your own hardware. Or one hosted in the EU. You choose.", Side.BOTTOM_LEFT, 1.5);
                looks(4.4, 1.3, near(by(this, "This computer", -46, -150, 690, 420), 0, 2.0, roomBeside(Side.BOTTOM_LEFT)));
                pointsOut(4.9, 7.1, by(this, "This computer", -14, -12, 640, 168));
                looks(10.3, 1.2, this::whole);
            }
        };
    }

    /// The window from wide to as narrow as a phone and back, by day, and in other languages.
    private static Scene converge() {
        return new AppScene("converge", 16) {
            @Override GeniesState start() { return Story.afterTheApp().select(Story.REX).withPage(GeniesState.Page.SETTINGS); }
            {
                says(1.3, 15.4, "Fits any screen", "From a wide monitor down to the width of a phone. By night or by day. In your language.");
                cutsIn(); afterTheApp();
                pressCard(0.9, "Juniper", () -> window.change(it -> it.select(Story.JUNIPER)));
                pointerLeaves(1.4);
                at(7.6, () -> { StagedWindow.ownFont(); window.change(it -> it.withPalette(Palette.LIGHT)); StagedWindow.ownFont(); });
                at(11.6, () -> window.change(it -> it.withLang(Lang.GERMAN)));
                at(12.7, () -> window.change(it -> it.withLang(Lang.SPANISH)));
                at(13.8, () -> { window.change(it -> it.withLang(Lang.ENGLISH).withPalette(Palette.DARK)); StagedWindow.ownFont(); });
                // The window draws itself into a point of light, which the next scene has
                // burst into the genies one might ask.
                cutsOut();
                looksGently(16.0, 1.3, () -> new Camera(window.width() / 2, window.height() / 2, 0.012).with(Effects.WISHES.x, Effects.WISHES.y));
            }
            @Override void paint(java.awt.Graphics2D g, double t) {
                double narrow = Draw.ease(t, 2.6, 6.2) - Draw.ease(t, 8.4, 11.0);
                window.size(Draw.mix(WIDE, 400, narrow), TALL);
                super.paint(g, t);
            }
            @Override void above(java.awt.Graphics2D g, double t) {
                Effects.light(g, Effects.WISHES, Draw.ease(t, 15.0, 16.0));
            }
            /// The ground goes with the window into the point of light, and leaves the night.
            @Override double drawnIn(double t) { return Draw.ease(t, 14.3, 16.0); }
        };
    }

    /// The user asks Juniper for a small app, and watches her build it on her desktop.
    private static Scene ask() {
        return new AppScene("ask", 23.5) {
            @Override GeniesState start() {
                return Story.genies().update(Story.JUNIPER, it -> it.waking().awake());
            }

            private Optional<Rectangle2D> composer() {
                return window.all(javax.swing.JTextArea.class).stream().findFirst().map(it -> window.unitsOf(StagedWindow.above(it, 3)));
            }
            private Optional<Rectangle2D> desktop() {
                return window.component(it -> it.getClass().getSimpleName().equals("DesktopScreen")).map(window::unitsOf);
            }

            {
                cutsIn(); cutsOut();
                // The words first; then near the message box while the wish is written; then
                // with the genie as she answers; then on her desktop, where the app appears.
                says(0.2, 8.6, "Ask for anything", "Say what you want, in your own words.", Side.TOP_LEFT, 1.5);
                looks(2.9, 1.0, near(() -> composer().map(it -> new Rectangle2D.Double(it.getX() - 10, it.getY() - 150, 620, 230)), 0, 2.6));
                double written = write(3.0, "Build me a little app that reminds me when to water my plants.", 30);
                pointsOut(3.0, written + 0.5, this::composer);
                press(written + 0.5, "Send");
                looks(written + 1.9, 1.1, near(() -> composer().map(it -> new Rectangle2D.Double(it.getX() - 20, 86, 700, 400)), 0, 2.3));
                press(written + 3.3, "Desktop");
                pointerLeaves(written + 3.7);
                looks(written + 4.9, 1.4, this::whole);
                double t = written + 1.5;
                at(t, () -> hears(new PiEvent.Thinking("A small web app will do: the plants, how often each wants water, and when it last got some.")));
                String plan = "On it. I'll build it right here in my lamp: a small web app that knows your plants and when each one is thirsty.";
                t = says(t + 1.2, plan, 90);
                at(t, () -> hears(new PiEvent.Answered(plan, "", 0, Story.MODEL)));
                t = Math.max(t, written + 4.6);
                at(t + 0.2, ART::openTerminal);
                t = uses(t + 0.3, "bash", "npm create vite@latest plants -- --template react", 1.2, "Scaffolding project in /home/agent/workspace/plants...\nDone.");
                prints(t - 1.1, 1, 5, 0.22);
                t = uses(t + 0.2, "write", "workspace/plants/src/App.jsx", 0.9, "Wrote 148 lines.");
                t = uses(t + 0.2, "bash", "npm run build && npm run preview -- --port 4173", 1.6, "✓ built in 1.31s\n➜  Local: http://localhost:4173/");
                prints(t - 1.5, 6, 13, 0.17);
                t = uses(t + 0.2, "bash", "lamp show \"your plant app\"", 0.9, "");
                prints(t - 0.8, 14, 15, 0.3);
                at(t - 0.25, ART::openApp);
                at(t, () -> window.genie(it -> it.shows("your plant app")));
                says(t - 5.2, 23.3, "Then watch it happen", "It writes the code, runs it, and shows you. On a desktop of its own.", Side.BOTTOM_LEFT, 0);
                looks(t + 1.6, 1.3, near(() -> desktop().map(it -> new Rectangle2D.Double(it.getX() - 30, it.getY() - 60, it.getWidth() + 50, it.getHeight() + 80)), 0, 1.75, roomBeside(Side.BOTTOM_LEFT)));
                pointsOut(t + 1.2, t + 3.4, this::desktop);
                String done = "Here it is. **Six plants** to start with, and the fern is due today. Tell me what to change.";
                double said = says(t + 0.4, done, 70);
                at(said + 0.1, () -> { hears(new PiEvent.Answered(done, "", 0, Story.MODEL)); hears(new PiEvent.Settled()); });
                looks(23.3, 1.2, this::whole);
            }

            /// The terminal prints its lines `from` to `to`, one each `apart` seconds, from `at`.
            private void prints(double at, int from, int to, double apart) {
                for (int line = from; line <= to; line++) {
                    int lines = line;
                    at(at + (line - from) * apart, () -> ART.printed(lines));
                }
            }
        };
    }
}
