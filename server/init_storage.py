from __future__ import annotations

import time
import os

from server.storage import create_script_storage


def main() -> None:
    storage = create_script_storage(required=True)
    if storage is None:
        raise RuntimeError("S3 storage configuration is required")
    last_error: Exception | None = None
    for attempt in range(30):
        try:
            storage.ensure_bucket()
            origins = [
                origin.strip()
                for origin in os.environ.get("FLOWPILOT_S3_CORS_ORIGINS", "").split(",")
                if origin.strip()
            ]
            storage.configure_cors(origins)
            return
        except Exception as error:
            last_error = error
            if attempt < 29:
                time.sleep(2)
    raise RuntimeError("Could not connect to the configured S3 bucket") from last_error


if __name__ == "__main__":
    main()
