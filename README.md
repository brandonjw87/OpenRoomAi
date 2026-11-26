# Open Area AI · v2.0

**Same room. Same stuff. Way more open floor.**

Open Area AI maps a room with your Android phone (ARCore, or sketch it by size), measures every
square foot of usable open floor and every walkway's real width, then tells you exactly what to
move, often just a few inches, to get the most open, flowing room your furniture allows.

> "Push the Sofa flush against Wall C (9½″) · +4.8 sq ft open floor"
> "Rotate the Coffee table 90° · out of the door swing · walkway to the Armchair 13″ → 23″"

## Download
**[⬇️ Open Area AI 2.0 for Pixel and most phones (arm64, 8.2 MB)](https://github.com/brandonjw87/OpenRoomAi/releases/download/v2.0/OpenAreaAI-v2.0-pixel-arm64.apk)**
· [Universal APK (14.8 MB)](https://github.com/brandonjw87/OpenRoomAi/releases/download/v2.0/OpenAreaAI-v2.0-universal.apk)
· [All releases](https://github.com/brandonjw87/OpenRoomAi/releases)

Open the link on your phone, tap the APK, and allow your browser to install apps if Android asks.
Android 8.0+. AR scanning uses Google Play Services for AR; sketch mode works on any phone.

## What's new in 2.0

### 1. ⚡ Precision Micro-Nudge Engine (the "3-Inch Optimizer")
Tries hundreds of small, realistic adjustments to the room as it is and ranks them by **usable
floor gained and walkway width gained**, per inch of effort:

- slides of 1, 2, 3, 4, 6, 9 and 12″ (or 2–30 cm) along each piece's own axes;
- **flush to the wall** (½″ left for baseboards) and **corner tucks**;
- **quarter turns** for tables and chairs, with a small shift to make them fit, or by moving the
  one neighbour that's in the way ("Rotate the coffee table 90° and slide the armchair 4″…");
- desks bring their chairs, beds their nightstands, sofas their coffee table.

Every candidate must leave **every single rule term no worse**: walls (concave rooms included),
overlaps, door swings, tall pieces in front of windows, and each piece's use zone. It can never
trade one clash for another. It also keeps 14–18″ of sofa legroom, chairs at their tables and
seats facing the screen. Candidates are screened in parallel on a 2″ grid, and the shortlist is
re-measured on a 1″ grid, so the numbers you see are the numbers you get (a unit test checks that).
Each nudge has **Preview** (ghost + arrow + live header deltas), **Apply** and **Undo**.

### 2. ⬜ Open-Zone & Flow Visualizer
- **Largest open zone:** the biggest unbroken open rectangle, aligned to the walls (maximal empty
  rectangle via row histograms and a monotonic stack), drawn with its exact size, e.g.
  "11′ × 7′ 8″ · 83.8 sq ft". It stays out of door swings.
- **Usable open floor:** a morphological opening with an 18″ disc over an exact Euclidean
  distance transform (Felzenszwalb–Huttenlocher). Slivers behind furniture don't count.
- **Flow:** the widest route from the door to every piece you walk up to and to the other doors.
  It uses a maximin "widest path", via union-find over clearance levels for speed or Dijkstra with
  an A* route for drawing. Lanes are colour-coded: **44″+ wide · 36–44″ clear · 30–36″ tight ·
  under 30″ bottleneck**. Bands show where lanes are tight, and every pinch point is labelled
  with its width and what it's between ("pinches between the Coffee table and Wall A").
- **What's limiting the space:** pinches, legroom (14–18″), 36″ to pull out dining chairs,
  24″ beside a bed, 36″ for a desk chair, pieces in a door swing, low pieces in the straight
  line from the door.

### 3. One-Tap Space Goals + Before/After
- **Max open center**: consolidate the open floor into one big zone.
- **Widest flow paths**: wide, straight walkways to everything, no pinch points.
- **Quick wins**: a chain of up to four micro-nudges, nothing heavy.

The optimizer now scores open space directly: zone size, usable floor and lane widths, weighted
per goal. It runs multi-start simulated annealing on all cores, then a final micro-nudge polish.
Pieces it left within 5 cm of where they stand go back exactly there, so plans never ask for
pointless inch-shuffles.

Results come as a **Before → After** card (usable floor, biggest zone, narrowest walkway,
walkways under 36″, places you can walk to, each widened walkway, openness score, design check
and how many moves are micro vs. bigger). A **Before / Compare / After** switch adds a draggable
wipe between the two plans.

Demo room, Max open center (typical test runs; the search is randomized): biggest open zone
**4′ 11″ × 4′ 9″ → 11–13′ × 7′ 10″ (+60–80 sq ft)**, narrowest walkway **13″ → 46–47″**, every
piece reachable.

### Also in 2.0
- **Inches & feet or cm & m** everywhere (default from your region, toggle on Home). Drags and
  size sliders snap to the inch or centimeter; walkway widths read in inches ("36″").
- **Undo / redo** for every edit, nudge and accepted plan (drags and slider moves count as one).
- **Concave rooms**: containment now catches a wall corner poking into a piece whose four corners
  are all inside (L-shaped rooms).
- AR-scanned rooms at any angle: rotations square up to the room's own walls, not the world axes.
- Temporary "park it here" spots in a move plan never land in a door swing.
- Editing the room while a plan is computing no longer gets overwritten by the stale plan.
- Room cards on Home are scored off the main thread.
- Saved rooms from earlier versions load cleanly, including retired settings.
- First-run "How it works", clearer empty states, honest error toasts.

## Still here from 1.x
AR scanning with raw depth, ARCore anchors, depth occlusion and on-device furniture recognition
(EfficientDet-Lite0 on LiteRT, 16 KB-page aligned) with real colours; AR ghost preview and
"place it in AR"; 3D dollhouse and first-person views; room uses (Everyday, Movie night, Work
from home, Entertaining); Will it fit?; Moving Day with door and hallway delivery checks;
sunlight simulator; Open-Look Studio (mirror finder, paint LRV); declutter coach; design-guideline
check; before/after GIF; Device Check; large-text mode.

## Build & test
```
./gradlew :app:assembleDebug          # APKs in app/build/outputs/apk/debug/
./gradlew :app:assembleRelease        # per-ABI + universal release APKs
./gradlew :app:testDebugUnitTest      # engine tests + Robolectric UI tests (screens in app/build/screens/)
./gradlew :app:lintDebug
```
Requires Android SDK 35 and JDK 17+; minSdk 26. AR needs an ARCore-supported phone (depth optional).
