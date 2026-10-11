PYTHON ?= python3
VENV_PYTHON := .venv/bin/python

.PHONY: setup dev test

setup:
	@if [ ! -x "$(VENV_PYTHON)" ]; then $(PYTHON) -m venv .venv; fi
	@if ! $(VENV_PYTHON) -m pip --version >/dev/null 2>&1; then \
		echo "pip is missing from .venv; install your OS python venv package (for example python3-venv) and rerun make setup." >&2; \
		exit 1; \
	fi
	@$(VENV_PYTHON) -m pip install -r server/requirements.txt

dev: setup
	@./scripts/dev.sh

test: setup
	@$(VENV_PYTHON) -m pytest server/tests -q
	@node --test demo/*.test.mjs
