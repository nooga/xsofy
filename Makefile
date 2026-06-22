# xsofy build & test targets.
#   lg          — the let-go binary (override: make LG=./bin/lg ...)
#   DIST        — wasm build output dir
LG   ?= lg
DIST ?= dist

.DEFAULT_GOAL := help

.PHONY: help test smoke-lg rune-font wasm e2e browser-smoke clean

help: ## Show this help
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

test: ## Run the let-go test suite
	$(LG) xsofy/test/run.lg

smoke-lg: ## Layer-1 headless runtime smoke (frozen-title guard + render-survives-turns)
	LG=$(LG) python3 xsofy/test/check_smoke.py

rune-font: ## Regenerate the inlined terminal font in tools/xsofy-shell.html from xsofy/*.lg
	# On-demand only (regenerates a committed asset, not a per-build artifact):
	# re-run after the game starts drawing a glyph it didn't before.
	uv run --with fonttools --with brotli python3 tools/gen-terminal-font.py

wasm: ## Build the WASM web app into $(DIST): client-owned shell + COI/?seed= bridges
	# Build glue-only (no let-go xterm shell), then inject xsofy's own shell.
	# Requires a let-go with -w-shell none (nooga/let-go, merged in #245).
	$(LG) -w $(DIST) -w-shell none main.lg
	XSOFY_WASM_INDEX=$(DIST)/index.html $(LG) tools/inject_shell.lg
	# COI bounded-retry and the ?seed=/?replay= env bridge still patch the core
	# glue: they touch the COI lifecycle and the worker VM env, which the shell
	# contract (window.LetGoHost) does not cover. Tracked as follow-up seams.
	XSOFY_WASM_INDEX=$(DIST)/index.html $(LG) tools/patch_wasm_coi.lg
	XSOFY_WASM_INDEX=$(DIST)/index.html $(LG) tools/patch_wasm_seed.lg

e2e: wasm ## Build+patch the bundle, then run the headless-WASM @playwright/test suite (boot + seeded regression)
	cd tests/e2e && npm install --no-audit --no-fund && npx playwright install chromium
	cd tests/e2e && DIST="$(CURDIR)/$(DIST)" npx playwright test

browser-smoke: wasm ## Run main's node smoke gate (boot-to-title) against the built bundle
	cd tests/browser && npm install --no-audit --no-fund && npx playwright install chromium
	cd tests/browser && node smoke.mjs --dir ../../$(DIST)

clean: ## Remove build output and test artifacts
	rm -rf $(DIST) tests/e2e/test-results tests/e2e/playwright-report tests/browser/smoke-failure.png
