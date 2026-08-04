from datetime import datetime, timezone


def utc_now() -> datetime:
    """Returns the current UTC time as a naive datetime.

    Every timestamp column in this project is `TIMESTAMP WITHOUT TIME ZONE` (see the
    migrations), matching the other language implementations in this repository, and asyncpg
    rejects a timezone-aware value for such a column. The naive value is therefore produced by
    dropping the tzinfo from an aware UTC reading rather than by calling `datetime.utcnow()`,
    which Python 3.12 deprecates.

    A project that stores `TIMESTAMPTZ` instead should return the aware value directly — see
    the timezone rule in docs/conventions.md.
    """
    return datetime.now(timezone.utc).replace(tzinfo=None)
