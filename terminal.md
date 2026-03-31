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
    "\u001b"   :escape
    "q"        :quit
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

## Gotchas

- **Coordinates are 1-based** — `(term/write-at 1 1 "@")` is the top-left corner.
- **No newlines in raw mode** — `println` still works but adds `\n` which moves the cursor down. Use `term/write` or `term/write-at` instead.
- **`read-key` is blocking** — the game loop will wait for input. This is fine for a turn-based roguelike. For real-time, you'd need `core.async` with a key-reading goroutine.
- **Escape key ambiguity** — pressing Escape sends `\u001b` but so do arrow keys (`\u001b[A`). `read-key` reads all available bytes at once so arrow keys come as a single 3-byte string, but there can be edge cases with slow terminals.
- **Always clean up** — if the process crashes without calling `restore-mode!`, run `reset` in the shell to fix the terminal.
- **`size` can change** — if the user resizes the terminal during play, `size` will return the new dimensions on next call. Consider re-rendering on size change.
