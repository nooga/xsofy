# Terminal I/O in let-go

The `term` namespace provides raw terminal control — everything you need for a roguelike. No external dependencies beyond what's already in `lg`.

## Setup

```clojure
(ns my-game
  (:require [term]
            [os]))
```

## Lifecycle

Every terminal app follows the same pattern: enter raw mode, use alternate screen, clean up on exit.

```clojure
(defn init-terminal []
  (when (nil? (term/size))
    (println "Not a terminal.")
    (os/exit 1))
  (term/raw-mode!)
  (term/alternate-screen)
  (term/hide-cursor)
  (term/clear))

(defn shutdown-terminal []
  (term/show-cursor)
  (term/main-screen)
  (term/reset-style)
  (term/restore-mode!))
```

**Always call `shutdown-terminal` before exiting** — if you don't, the user's shell will be stuck in raw mode. Wrap your main loop in `try`/`finally`:

```clojure
(defn run-game []
  (init-terminal)
  (try
    (game-loop)
    (finally
      (shutdown-terminal))))
```

## API Reference

### Terminal mode

| Function | Args | Returns | Description |
|---|---|---|---|
| `raw-mode!` | none | `true` or `nil` | Enter raw mode (keypresses delivered immediately, no echo). Returns `nil` if not a TTY. Idempotent. |
| `restore-mode!` | none | `true` or `nil` | Restore original terminal state. |

### Screen

| Function | Args | Returns | Description |
|---|---|---|---|
| `size` | none | `[cols rows]` or `nil` | Terminal dimensions. `nil` if not a TTY. |
| `clear` | none | `nil` | Clear entire screen. |
| `clear-line` | none | `nil` | Clear current line. |
| `alternate-screen` | none | `nil` | Switch to alternate screen buffer (preserves user's scrollback). |
| `main-screen` | none | `nil` | Switch back to main screen buffer. |

### Cursor

| Function | Args | Returns | Description |
|---|---|---|---|
| `move-cursor` | `col row` | `nil` | Move cursor to position. **1-based** (ANSI convention). |
| `hide-cursor` | none | `nil` | Hide the cursor. |
| `show-cursor` | none | `nil` | Show the cursor. |

### Output

| Function | Args | Returns | Description |
|---|---|---|---|
| `write` | `str` | `nil` | Write string at current cursor position. No newline. |
| `write-at` | `col row str` | `nil` | Move cursor then write. Equivalent to `move-cursor` + `write` but in one call. |
| `flush` | none | `nil` | Flush stdout. Call after a batch of writes to ensure display updates. |

### Input

| Function | Args | Returns | Description |
|---|---|---|---|
| `read-key` | none | `string` or `nil` | Blocking read of a single keypress. Returns `nil` on EOF. |

`read-key` returns raw bytes as a string:

- Regular keys: `"a"`, `"Z"`, `" "`, `"\r"` (enter), `"\t"` (tab)
- Ctrl combos: `"\u0001"` (ctrl-a), `"\u0003"` (ctrl-c), `"\u001b"` (escape)
- Arrow keys: `"\u001b[A"` (up), `"\u001b[B"` (down), `"\u001b[C"` (right), `"\u001b[D"` (left)
- Function keys: `"\u001bOP"` (F1), `"\u001bOQ"` (F2), etc.

### Parsing keys for a game

```clojure
(defn parse-key [k]
  (case k
    "\u001b[A" :up
    "\u001b[B" :down
    "\u001b[C" :right
    "\u001b[D" :left
    "\r"       :enter
    "\u001b"   :quit
    ;; default: return the key itself as a keyword if single char
    (if (= 1 (count k))
      (keyword k)
      :unknown)))
```

### Colors

| Function | Args | Returns | Description |
|---|---|---|---|
| `set-fg` | `code` or `r g b` | `nil` | Set foreground color. 1 arg = 256-color palette. 3 args = 24-bit RGB. |
| `set-bg` | `code` or `r g b` | `nil` | Set background color. Same calling convention. |
| `reset-style` | none | `nil` | Reset all colors and attributes to terminal default. |

### Text attributes

| Function | Args | Returns | Description |
|---|---|---|---|
| `bold` | none | `nil` | Enable bold text. |
| `underline` | none | `nil` | Enable underline. |
| `inverse` | none | `nil` | Swap foreground/background. |

Call `reset-style` to turn off all attributes.

### Utility

| Function | Args | Returns | Description |
|---|---|---|---|
| `char-width` | `str` | `int` | Display width of first character (always 1 for now). |

## 256-Color Palette Quick Reference

```
0-7     Standard colors  (black, red, green, yellow, blue, magenta, cyan, white)
8-15    Bright colors     (bright black, bright red, ...)
16-231  6x6x6 color cube  (16 + 36*r + 6*g + b, where r/g/b are 0-5)
232-255 Grayscale ramp    (dark to light)
```

Useful codes for a roguelike:

```clojure
(def colors
  {:floor     242  ; dark gray
   :wall      255  ; white
   :player    226  ; bright yellow
   :enemy     196  ; bright red
   :item      51   ; cyan
   :fire      202  ; orange
   :water     27   ; blue
   :poison    46   ; green
   :magic     129  ; purple
   :gold      220  ; gold
   :dark      236  ; very dark gray (fog of war)
   })
```

## Rendering a Tile Grid

For a roguelike, render the map as a grid of characters with colors. Use `byte-array` for the tile grid and a map for colors:

```clojure
(def tile-chars
  {0 "."   ; floor
   1 "#"   ; wall
   2 "~"   ; water
   3 "+"   ; door
   4 "<"   ; stairs up
   5 ">"   ; stairs down
   })

(defn render-map [grid width height offset-x offset-y]
  (dotimes [y height]
    (dotimes [x width]
      (let [tile (aget grid (+ x (* y width)))
            ch (get tile-chars tile ".")]
        (term/write-at (+ x offset-x) (+ y offset-y) ch)))))
```

For better performance, batch a full row into a single string:

```clojure
(defn render-row [grid width y offset-x offset-y]
  (let [row-str (apply str
                  (map (fn [x]
                         (get tile-chars (aget grid (+ x (* y width))) "."))
                       (range width)))]
    (term/write-at offset-x (+ y offset-y) row-str)))

(defn render-map-fast [grid width height offset-x offset-y]
  (dotimes [y height]
    (render-row grid width y offset-x offset-y))
  (term/flush))
```

## Game Loop Pattern

```clojure
(defn game-loop [state]
  (render state)
  (term/flush)
  (let [key (parse-key (term/read-key))]
    (if (= key :quit)
      state
      (recur (update-state state key)))))
```

## Responsive UIs: multiplexing input and a cosmetic clock

The blocking game loop above is fine for a turn-based screen where **nothing
moves while you wait for input**. The moment a screen needs to *animate while
idle* — a pulsing title, drifting particles, ambient effects — a blocking
`read-key` falls apart:

- **Native:** the loop is stuck in `read-key` until a key arrives, so frames
  never advance and the animation freezes.
- **WASM (browser):** far worse. `read-key` is backed by `Atomics.wait` on a
  SharedArrayBuffer, and Go-wasm is single-threaded/cooperative, so a blocking
  read freezes the **entire** worker — including any timer goroutine driving
  animation. The screen never paints past frame 0.

The fix is a **poll-based source contract**: never block while idle. Peek input
with the non-blocking `term/key-pending?` and only call `read-key` once a key is
actually queued; meanwhile a cosmetic clock advances the animation. The same
loop then runs identically native and in wasm.

xsofy implements this as `xsofy.ui/run-loop` (see
`docs/responsive-ui-event-loop-design.md`). A screen describes **which sources
it listens to** and **how state folds over events**; the loop drives render:

```clojure
(ui/run-loop
  {:sources  [(ui/input-source) (ui/clock-source 120)]  ; non-blocking polls
   :state    {:world world :ui {:frame 0}}
   :step     (fn [state [tag payload]]                   ; the scan
               (case tag
                 :action (handle-key state payload)      ; drives :world
                 :tick   (assoc-in state [:ui :frame] payload)  ; animates :ui
                 state))
   :render   (fn [state] (draw state) (term/flush))      ; paints AND presents
   :frame-ms 120})                                       ; floored to ≥30ms in wasm
```

- **Sources** are non-blocking polls (`() → seq-of-events`). `input-source`
  drains queued keys (parsed to `[:action …]` at the edge); `clock-source`
  emits `[:tick frame]` once an interval elapses; `replay-source` feeds a
  recorded action log; `poll-source` wraps any non-blocking `() → events`.
- **`step`** is a `scan` over `{:world :ui}`; it returns `(reduced result)` to
  exit the loop. Modal/multi-step screens compose as **nested `run-loop`s** that
  return an assembled value — in xsofy every interactive screen runs this way
  (title, death, the turn loop, and the modal menus: inventory, quick-menu,
  messages, help, rune-codex, inscribe, and the y/n confirms), so **no screen
  blocks on `read-key`**. A nested loop drains input each frame and exits on the
  first relevant key (`prompt-loop`) or once its handler changes `:screen`
  (`modal-input-loop`).
- **Pacing** uses a wasm-aware `pause!` (`(<!! (timeout (max ms 30)))`, *not*
  `Thread/sleep`) so the JS event loop actually yields and the input buffer
  refills.

### Determinism caveat (important)

If your simulation is replay-deterministic from a `(seed, action-log)` pair,
**cosmetic clock ticks must never enter that stream**. Keep loop state split:

```clojure
{:world <deterministic sim>   ; the only thing dispatch/replay ever see
 :ui    <cosmetic frame/particles>}  ; advanced by [:tick], never logged
```

Route `[:tick …]` to `:ui` only — never fold it into the seed or append it to
the action log. Replay then reconstructs `:world` bit-identically regardless of
how (or whether) animation ran.

### Note on `key-pending?` and async sources

`term/key-pending?` is the portable primitive that makes the poll-on-tick
contract work in both builds. Genuinely async sources (network, multi-timer)
are **polling-only** today: let-go's channel ops (`<!`/`>!`) are blocking, with
no non-blocking `poll!`/`alts!` yet (tracked as nooga/let-go#194), so a push
`chan-source` adapter is a documented seam, not yet implemented.

## Gotchas

- **Coordinates are 1-based** — `(term/write-at 1 1 "@")` is the top-left corner.
- **No newlines in raw mode** — `println` still works but adds `\n` which moves the cursor down. Use `term/write` or `term/write-at` instead.
- **`read-key` is blocking** — the game loop will wait for input. This is fine for a turn-based screen with no idle animation. For anything that animates while waiting (or any wasm build), don't reach for a key-reading goroutine — under single-threaded Go-wasm a blocking `read-key` freezes the whole runtime. Use the poll-based [Responsive UIs](#responsive-uis-multiplexing-input-and-a-cosmetic-clock) pattern (`key-pending?` + `xsofy.ui/run-loop`) instead.
- **Escape key ambiguity** — pressing Escape sends `\u001b` but so do arrow keys (`\u001b[A`). `read-key` reads all available bytes at once so arrow keys come as a single 3-byte string, but there can be edge cases with slow terminals.
- **Always clean up** — if the process crashes without calling `restore-mode!`, run `reset` in the shell to fix the terminal.
- **`size` can change** — if the user resizes the terminal during play, `size` will return the new dimensions on next call. Consider re-rendering on size change.
