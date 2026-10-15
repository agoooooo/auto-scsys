PYTHON ?= python3
VENV_PYTHON := .venv/bin/python

.PHONY: setup console-install dev test

setup:
	@if [ ! -x "$(VENV_PYTHON)" ]; then $(PYTHON) -m venv .venv; fi
	@if ! $(VENV_PYTHON) -m pip --version >/dev/null 2>&1; then \
		echo "pip is missing from .venv; install your OS python venv package (for example python3-venv) and rerun make setup." >&2; \
		exit 1; \
	fi
	@$(VENV_PYTHON) -m pip install -r server/requirements.txt

console-install:
	@if [ ! -d console/node_modules ]; then npm ci --prefix console; fi

dev: setup console-install
	@./scripts/dev.sh

test: setup console-install
	@$(VENV_PYTHON) -m pytest server/tests -q
	@node --test demo/*.test.mjs
	@node --test website/*.test.mjs
	@npm --prefix console run typecheck
	@npm --prefix console test
	@npm --prefix console run build
