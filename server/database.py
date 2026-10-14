from __future__ import annotations

import re
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator


POSTGRES_SCHEMES = ("postgresql://", "postgresql+psycopg://")
_QUESTION_MARK = re.compile(r"\?")


class DatabaseRow(dict):
    def __getitem__(self, key: Any) -> Any:
        if isinstance(key, int):
            return tuple(self.values())[key]
        return super().__getitem__(key)


class DatabaseResult:
    def __init__(self, cursor: Any):
        self._cursor = cursor
        self.rowcount = cursor.rowcount

    @staticmethod
    def _row(row: Any) -> DatabaseRow | None:
        return DatabaseRow(row) if row is not None else None

    def fetchone(self) -> DatabaseRow | None:
        return self._row(self._cursor.fetchone())

    def fetchall(self) -> list[DatabaseRow]:
        return [DatabaseRow(row) for row in self._cursor.fetchall()]

    def __iter__(self) -> Iterator[DatabaseRow]:
        for row in self._cursor:
            yield DatabaseRow(row)


class DatabaseConnection:
    def __init__(self, connection: Any, *, postgres: bool):
        self._connection = connection
        self.postgres = postgres

    def execute(self, statement: str, parameters: Any = ()) -> DatabaseResult:
        if not self.postgres:
            return DatabaseResult(self._connection.execute(statement, parameters))
        try:
            cursor = self._connection.execute(_QUESTION_MARK.sub("%s", statement), parameters)
        except Exception as error:
            import psycopg

            if isinstance(error, psycopg.IntegrityError):
                raise sqlite3.IntegrityError(str(error)) from error
            if isinstance(error, psycopg.Error):
                raise sqlite3.OperationalError(str(error)) from error
            raise
        return DatabaseResult(cursor)

    def executemany(self, statement: str, parameters: Any) -> DatabaseResult:
        if not self.postgres:
            return DatabaseResult(self._connection.executemany(statement, parameters))
        try:
            cursor = self._connection.cursor()
            cursor.executemany(_QUESTION_MARK.sub("%s", statement), parameters)
        except Exception as error:
            import psycopg

            if isinstance(error, psycopg.IntegrityError):
                raise sqlite3.IntegrityError(str(error)) from error
            if isinstance(error, psycopg.Error):
                raise sqlite3.OperationalError(str(error)) from error
            raise
        return DatabaseResult(cursor)

    def executescript(self, script: str) -> None:
        if not self.postgres:
            self._connection.executescript(script)
            return
        for statement in script.split(";"):
            if statement.strip():
                self.execute(statement)

    def table_columns(self, table: str) -> set[str]:
        if self.postgres:
            rows = self.execute(
                "SELECT column_name FROM information_schema.columns "
                "WHERE table_schema=current_schema() AND table_name=?",
                (table,),
            ).fetchall()
            return {row["column_name"] for row in rows}
        return {row["name"] for row in self.execute(f"PRAGMA table_info({table})")}

    def begin_immediate(self) -> None:
        if self.postgres:
            self.execute("SELECT pg_advisory_xact_lock(731248901)")
        else:
            self.execute("BEGIN IMMEDIATE")

    def lock_device(self, device_id: str) -> None:
        if self.postgres:
            self.execute("SELECT id FROM devices WHERE id=? FOR UPDATE", (device_id,))
        else:
            self.begin_immediate()

    def lock_task(self, task_id: str) -> None:
        if self.postgres:
            self.execute("SELECT id FROM tasks WHERE id=? FOR UPDATE", (task_id,))
        else:
            self.begin_immediate()

    def commit(self) -> None:
        self._connection.commit()

    def rollback(self) -> None:
        self._connection.rollback()

    def close(self) -> None:
        self._connection.close()


@contextmanager
def connect_database(database: str | Path) -> Iterator[DatabaseConnection]:
    database_value = str(database)
    postgres = database_value.startswith(POSTGRES_SCHEMES)
    if postgres:
        import psycopg
        from psycopg.rows import dict_row

        normalized_url = database_value.replace("postgresql+psycopg://", "postgresql://", 1)
        connection = psycopg.connect(normalized_url, row_factory=dict_row)
    else:
        connection = sqlite3.connect(database_value, timeout=10, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
    wrapped = DatabaseConnection(connection, postgres=postgres)
    try:
        yield wrapped
        wrapped.commit()
    except Exception:
        wrapped.rollback()
        raise
    finally:
        wrapped.close()
