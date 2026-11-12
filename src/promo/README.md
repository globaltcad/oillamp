# 🎬 The promo film

Genies' introduction video, made by a program: `./gradlew promo` films it into
`build/promo/genies-promo.mp4`. Change the app, or a word of the storyboard, and film it again.

```sh
./gradlew promo                                  # the whole film, 1920 × 1080, 30 pictures a second
./gradlew promo --args="--fps 60"                # the same, smoother, in twice the time
./gradlew promo --args="--list"                  # the scenes, and when each begins
./gradlew promo --args="--scenes ask,history"    # only these scenes, into build/promo/cut.mp4
./gradlew promo --args="--still ask@12.5"        # one picture, into build/promo/stills/
./gradlew promo --args="--outline ask@12.5"      # the window's parts at that moment, to name one in a scene
```

It needs `ffmpeg` on the path, and a graphical session, as Genies does. No window opens.
The whole film takes about five minutes to make, twice that at 60 pictures a second.

Scenes hand over to one another at points of light that both know (`Effects.SEED`, `WISHES`, `LAMP`):
one scene draws what it shows into the point, and the next has it burst into what it begins with.

## What is real in it, and what is staged

**The window is the real one.** The scenes in the app show `GeniesView`, as `dev.gui.Main` opens
it, laid out and painted by the app's own code, at whatever size a scene gives it. It is never
shown on screen: each picture of the film is painted from it directly.

**What it shows is staged.** Behind the window is `StagedGenies`, not the app: no lamp is lit and
no model is asked. The storyboard plays the app's part by changing the window's one value,
`GeniesState`, as the app would: a genie wakes, an answer arrives piece by piece, a tool runs.
The genie's desktop is `StandInDesktop`, which speaks VNC as a lamp's desktop does, so the app's
own viewer connects to it and draws what `DesktopArt` paints: a lamp's wallpaper and dock, a
terminal, and the plant app. So the film is the same each time, and needs neither podman nor a model.

**The lamp and the genies** in the scenes without the window are the app's own welcome
(`WelcomeScene`) and its own genie pictures, borrowed through `dev.gui.view.PromoArt`.

## Where to change what

| To change | Look in |
|---|---|
| the scenes, their order and length, every word said, what is pressed and when | `Storyboard` |
| who is in the film: the genies, their conversations, jobs, history, access rules | `Story` |
| the first scene: the devices, the hands that reach for them, the camera on it | `Problem` |
| the pictures of the other scenes without the window | `Acts` |
| what the genie's desktop shows | `DesktopArt` |
| the caption, the pointer, typing and answering, for every scene in the app | `AppScene` |
| how words are drawn into a point of light, the point bursts, and a border glows | `Effects` |
| how the window is seen at an angle, and what is soft | `Lens` |
| the dusk and the swirling sand behind the window, when it comes up, and how it is drawn in at the end | `Sand`, `AppScene.lit`, `AppScene.drawnIn` |
| the card under a caption, and where it rests: above or below the window, or in the corner where it is least in the way, chosen per caption | `AppScene.card`, `AppScene.Side`, `Storyboard` |
| where in the music the film begins | `Score.BEGINS_AT` |

A scene sets itself up from nothing, so `--scenes` and `--still` film any of them alone.

## The camera, and parts of the window

A scene in the app (`AppScene`) does not show the window as a flat picture. It has:

- **A camera** (`Camera`): the point of the window in the middle of the picture, and how many
  pixels of the film a unit of the window is. `looks(at, glide, view)` has it glide to a view;
  `near(place, …)` is a view of a place in the window, and `whole()` the whole window under the
  caption. Views are looked up anew for each picture, so they follow a window that changes.
  When the camera is nearer than the window's own pixels are fine, the window is painted at a
  whole multiple of them, so a close view is as sharp as a wide one.
- **Parts** (`Pose`): `holds(part, pose)` holds a component of the window apart from it. While
  its pose is not `Pose.LANDED`, the window is painted without it, and the part as a layer of
  its own, with nothing behind it: away, flying in (`Pose.landing`), growing out of a point
  (`Pose.popping`), or fading in (`Pose.fading`). With `windowShown` at 0, only such parts are
  in the picture: a button before there is a window, as in the scene `wake`.
- **What is pointed out**: `pointsOut(from, until, place)` puts a border of the flame's colour
  around a place in the window, with a light that runs around it, for when the camera has
  come to something and the viewer should see what.
- **What fades where it was**: `fades(at, seconds, part)` keeps how a part looks at a moment and
  lets that fade in its place, whatever the window does with the part: the Wake button, which
  the window no longer has once it was pressed.
- **A lens** (`Lens`): a scene's `tilt(t)` has the window seen at an angle, as a surface with
  depth, and soft above and below a sharp band, as a photograph of something small is. Parts
  that land meanwhile (`Pose.dropping`) come down onto that surface. The pointer does not
  follow the angle, so a scene tilts only while nothing is pressed.
- **Places by what they say**: parts and places are found in the window as it is, by the words
  of a button or a label near them (`window.buttonSaying`, `labelSaying`, `StagedWindow.above`),
  never by coordinates. `--outline` lists what is there. If the app words something anew, the
  scene that names it must follow; a place that is not found leaves the camera on the whole window.
- **A caption that takes turns with the app** (`says(from, until, headline, line, side, beat)`):
  for the beat, the words have the picture to themselves, large and in its middle, over a
  window gone dim; then they glide to rest at a side (`Side.TOP`, `BOTTOM`, `LEFT`, `RIGHT`), the
  window lights up again, and the camera goes to the detail, in the room beside the words
  (`roomBeside(side)`). A scene first does its thing in the app, then says what it was.
- **Words that come one by one** (`Letters`), and **scenes that follow one another
  without a break**: `cutsIn()` and `cutsOut()` keep the window in the picture from one scene to
  the next, each of which begins in the state the one before ended in (`Story.afterTheApp()`).

## The film's clock

Painting a picture takes longer than the picture is shown, so the window cannot be filmed while
it plays. The film has a clock of its own instead, `FilmClock`, which it moves on by one picture
at a time. `./gradlew promo` starts the JVM with it as its agent, and as SwingTree's and the
window's classes are loaded, their calls of `System.currentTimeMillis()` and `System.nanoTime()`
are turned into calls of the film's clock. So every animation of the window is where it would be
at each picture's moment, and the film comes out the same on a slow computer as on a fast one.
`Animations` has SwingTree step its animations for each picture, through two private members of
`swingtree.animation.AnimationRunner`; if SwingTree changes those, it says so.

The window is laid out at the app's own scale of 2, as on a screen of fine pixels, and what is
painted is brought to the film's size. It is never painted stretched by a fraction, which took
half a second a picture. What the window leaves to the desktop's look, such as the font of a
plain label, is set to the app's own (`StagedWindow.ownFont`), so that the film does not depend
on the desktop of whoever makes it.

## The score

See [music/README.md](music/README.md). The film is cut to a piece that is not in the repository.
