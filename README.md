# OpenRoom AI 🏠✨

Scan a room with your Android phone, get a floor plan of walls, doors, windows and furniture,
and a step-by-step plan for rearranging it so the room **feels as open as possible**.

## How it works
1. **AR scan (ARCore)** – detects the floor, you tap the room corners, then mark doors/windows.
   Furniture is auto-detected from the **Depth API** + feature points (top-down height map →
   blobs → minimum-area boxes, with a type guess from size/height). Missed something? Tap two corners.
2. **Openness engine** – rasterises the room and scores it 0–100 from: the biggest clear floor
   circle, walkable floor reachable from the door (60 cm min), sightlines from the doorway past tall
   furniture, daylight not blocked by tall pieces, and whether every piece can actually be reached.
3. **Optimizer** – simulated annealing with hard rules (inside the room, no overlaps, door swings
   and "use zones" clear) and soft goals (backs to walls, sofa faces TV, coffee table in reach,
   nightstands by the bed, desk near daylight, minimal lifting). Exploration runs on a 10 cm grid;
   the winner is judged on the same 5 cm grid as the report.
4. **Move planner** – orders the moves so nothing lands on something still standing there, parking
   a blocker in the open middle when needed, with plain directions ("Slide it 1.2 m toward Wall C").

## The 3 big extras
- **AR ghost preview** – see the new layout as glowing boxes in your real room, with arrows from
  where each piece is now. Works in the scan session, or re-align any saved room by tapping 2 corners.
- **Openness heatmap, walk paths & "view from the door"** – see squeeze points (<60 cm),
  tight spots (60–90 cm), the open heart of the room, and what's hidden from the entrance.
- **Will it fit?** – enter a product's size before buying; the whole room is re-planned around
  it and you get a verdict, the openness cost, and what else has to move.

## v1.1: 3 more big features
- **3D walkthrough**: orbit a dollhouse model of your room, or step into first person:
  "From the door" (your first impression) and "From your sofa/bed". Toggle Now ↔ Optimized
  and watch the furniture glide into place. Software renderer with near-plane clipping,
  painter's sort and per-type low-poly furniture (sofas with arms, tables on legs, TV on stand…).
- **Lifestyle goals**: Everyday, 🍿 Movie night (seats face the screen at 1.8–3.4 m, no window
  glare), 💻 Work from home (side-lit desk out of the doorway traffic), 🎉 Party mode (everything
  to the walls, max dance floor), ♿ Wheelchair friendly (90 cm paths + 1.5 m turning circle).
- **Declutter coach**: re-scores the room for every "remove / go compact / swap tall for low"
  option and ranks them by real openness gain (+N), with one-tap Apply & re-plan.
  It will never tell you to throw out your bed.

Also: sketch mode for phones without ARCore, demo room, lock pieces in place, 3 effort levels
(Quick tidy / Balanced / Full redesign), before→after morph animation, progress checklist, and
share the plan as an image + text checklist.

## Build
```
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # engine tests + Robolectric screenshots in app/build/screens/
```
Requires Android SDK 35, JDK 17+. minSdk 26. AR needs an ARCore-supported device (depth optional).
