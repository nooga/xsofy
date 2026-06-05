# xsofy build & test targets.
#   lg          — the let-go binary (override: make LG=./bin/lg ...)
#   DIST        — wasm build output dir
#   STDCLJ      — Standard Clojure Style formatter (--file-ext lg treats .lg as Clojure)
LG     ?= lg
DIST   ?= dist
STDCLJ ?= standard-clj
NREPL_PORT ?= 2137
PORT   ?= 8123

.DEFAULT_GOAL := help

.PHONY: help test smoke-lg wasm e2e browser-smoke clean fmt fmt-check nrepl serve

help: ## Show this help
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

test: ## Run the let-go test suite
	$(LG) xsofy/test/run.lg

smoke-lg: ## Layer-1 headless runtime smoke (frozen-title guard + render-survives-turns)
	LG=$(LG) python3 xsofy/test/check_smoke.py

wasm: ## Build the WASM web app into $(DIST) and apply COI + ?seed= patches
	$(LG) -w $(DIST) main.lg
	XSOFY_WASM_INDEX=$(DIST)/index.html $(LG) tools/patch_wasm_coi.lg
	XSOFY_WASM_INDEX=$(DIST)/index.html $(LG) tools/patch_wasm_seed.lg

e2e: wasm ## Build+patch the bundle, then run the headless-WASM @playwright/test suite (boot + seeded regression)
	cd tests/e2e && npm install --no-audit --no-fund && npx playwright install chromium
	cd tests/e2e && DIST="$(CURDIR)/$(DIST)" npx playwright test

serve: ## Serve the built $(DIST) on http://localhost:$(PORT) with COI headers (open ?seed= / ?replay=). Run `make wasm serve` to rebuild first.
	@echo "Serving $(DIST) on http://localhost:$(PORT)  (Ctrl-C to stop)"
	COI=1 PORT=$(PORT) DIST="$(CURDIR)/$(DIST)" node tests/e2e/static-server.js

browser-smoke: wasm ## Run main's node smoke gate (boot-to-title) against the built bundle
	cd tests/browser && npm install --no-audit --no-fund && npx playwright install chromium
	cd tests/browser && node smoke.mjs --dir ../../$(DIST)

fmt-check: ## Check Standard Clojure Style on .clj/.cljs/.cljc/.lg (no changes)
	$(STDCLJ) check --file-ext lg .

fmt: ## Format .clj/.cljs/.cljc/.lg to Standard Clojure Style (modifies files)
	$(STDCLJ) fix --file-ext lg .

nrepl: ## Start the let-go nREPL on $(NREPL_PORT) (drive via the nrepl MCP server / .mcp.json)
	$(LG) -n -p $(NREPL_PORT)

clean: ## Remove build output and test artifacts
	rm -rf $(DIST) tests/e2e/test-results tests/e2e/playwright-report tests/browser/smoke-failure.png
