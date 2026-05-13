# Xs of Y

A roguelike written in my [lisp](https://github.com/nooga/let-go), where the magic system is a lisp. 

> Note: This is not finished! It's playable but mild peril and uscheduled explosions are to be expected.

**[Play in your browser](https://nooga.github.io/xsofy/)**

![screenshot](xsofy.gif)

Every run generates a new title (_Gazebos of Mounting Dread_), a new quest (_retrieve the Spatula of Futility_), and a new set of rune mappings. The runes are secretly symbols, spells are s-expressions. You have root access to the dungeon's reality engine but the man pages are in a dead language that changes every boot.

The power curve is inverted - early game is desperate survival, late game is applied theology with inadequate safety margins.

Meanwhile the dungeon is trying to kill you through more conventional means. Spiders shoot web cones that trap you while goblins close in. Slimes split when you hit them. Trolls regenerate. Set something on fire and it panics, runs through grass, ignites the grass, ignites more creatures - it's fine, everything is fine. Push an ogre into lava. Push a goblin into another goblin. Push yourself into a chasm by accident. Chasms are educational.

Written in ~6900 lines of [let-go](https://github.com/nooga/let-go) - a Clojure dialect on a Go bytecode VM. Persistent data structures all the way down. No dependencies. 6ms startup. Runs natively or [in the browser](https://nooga.github.io/xsofy/) via WASM.

If you like how this game looks check out [Brogue](https://sites.google.com/site/broguegame/) - my main inspiration.

## Running

```bash
lg main.lg
```

Get `lg` from [let-go](https://github.com/nooga/let-go), or:

```bash
brew tap nooga/let-go https://github.com/nooga/let-go
brew install let-go
```
