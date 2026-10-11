from __future__ import annotations

import argparse
import getpass
import os
import sys

from server.app import create_initial_admin


def main() -> int:
    parser = argparse.ArgumentParser(description="Create the first administrator in a fresh FlowPilot database.")
    parser.parse_args()

    database_path = os.environ.get("FLOWPILOT_DB_PATH", "").strip()
    if not database_path:
        print("Set FLOWPILOT_DB_PATH to the target database before creating the initial administrator.", file=sys.stderr)
        return 2

    name = input("Administrator name: ").strip()
    email = input("Administrator email: ").strip()
    password = getpass.getpass("Administrator password (12+ characters): ")
    confirmation = getpass.getpass("Confirm password: ")
    if password != confirmation:
        print("Passwords do not match.", file=sys.stderr)
        return 2

    try:
        user_id = create_initial_admin(database_path, name, email, password)
    except (OSError, ValueError) as error:
        print(f"Administrator was not created: {error}", file=sys.stderr)
        return 1
    print(f"Initial administrator created: {email} ({user_id})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
