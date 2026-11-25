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

## v1.2: faster + 2 more features
- **⚡ Multi-core optimizer**: independent annealing chains run in parallel (one per core, 4–8),
  each with its own grid and RNG. Demo room: 4.7 s → 1.9 s per run on 4 cores, and better
  results (mean openness 76 → 79, worst case 72 → 77 over 6 seeds).
- **☀️ Sunlight simulator**: uses the compass (rotation-vector sensor, true-north corrected) and
  your coarse location. AR scans record which way the room faces automatically; sketched rooms
  calibrate by pointing the camera at Wall A. Traces sun rays through every window onto the
  floor with tall-furniture shadows, for any hour and season (today, midsummer, equinox,
  midwinter), plus an all-day "sun hours" map. Flags TV glare, sun on desks and beds, and
  plants that need more light — and the optimizer now nudges plants into the sun and screens
  out of the glare.
- **🎬 Glow-up GIF**: one tap renders an orbiting 3D before → rearranging → after animation with
  the score counting up, encoded by a built-in GIF encoder (median-cut palette, LZW), and opens
  the share sheet. ~1.4 MB, 72 frames, under a second to render on a laptop-class CPU.

## v1.3: research-driven upgrade + Moving Day + Open-Look Studio

### What the research changed
- **Interior-design guideline terms** from furniture-layout research — conversation, visual
  balance, alignment and emphasis (focal point), as in Merrell et al., *Interactive Furniture
  Layout Using Interior Design Guidelines* (SIGGRAPH 2011), with the simulated-annealing approach
  also used by Yu et al., *Make it Home* (2011). Implemented in `engine/DesignGuidelines.kt` as a
  0–100 **design check** (demo room: 73 → 85 after optimizing). Measured finding: adding these
  terms *inside* the anneal made both openness and design worse on the demo (bumpier cost
  landscape), so they judge finished candidates and drive insights instead.
- **AR scanning** follows Google's ARCore Raw Depth codelab (github.com/google-ar/codelab-raw-depth-api):
  raw depth + per-pixel confidence (keep ≥ 0.3), shorter trusted range, duplicate-frame skip by
  timestamp, and a 3 cm band filter around detected walls — using the plane polygon rather than
  the infinite plane the sample used. Falls back to smoothed depth where raw depth isn't offered.
- **Moving know-how** from furniture retailers' measuring guides: tilt pieces through doors using
  their "diagonal depth", allow for door stops, and plan hallway corners.
- **"Look bigger" tricks** from designers: mirror opposite the main light source, light paint,
  avoid visually heavy pieces — turned into computable tools below.

### 🚚 Moving Day
Copy furniture from your other rooms into this one and let the optimizer place it (old rooms stay
untouched). Every piece gets a delivery verdict: straight through, tilt it ("diagonal depth" from
its real side profile, rotated in the door plane), door off its hinges, legs off, both, take it
apart, or won't fit — plus a hallway-corner check using the corner-turn bound
`L_max = min_θ a/cosθ + b/sinθ − t(tanθ + cotθ)`, sliding round, tilting up, or standing it on end.
Ends with numbered labels and a shareable checklist for the movers.

### 🪞 Open-Look Studio
- **Mirror finder:** reflects your eye position across each candidate wall spot and traces what the
  mirror would show from the doorway and the sofa; rewards seeing the window ("a second window"),
  extra see-through depth, and facing the window to bounce daylight. Avoids doors, windows and
  tall furniture; hangs above low pieces. Hung mirrors appear on the plan, in 3D and in the GIF.
- **Paint:** palette with Light Reflectance Values (LRV) and a verdict per colour, previewed on the
  3D walls.
- **Design check:** conversation, visual balance (which wall it's heavy toward), alignment and
  focal point, with before → after in the results.

Also: sketch mode for phones without ARCore, demo room, lock pieces in place, 3 effort levels
(Quick tidy / Balanced / Full redesign), before→after morph animation, progress checklist, and
share the plan as an image + text checklist.

## Build
```
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # engine tests + Robolectric screenshots in app/build/screens/
```
Requires Android SDK 35, JDK 17+. minSdk 26. AR needs an ARCore-supported device (depth optional).
