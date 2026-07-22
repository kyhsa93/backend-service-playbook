import asyncio
import os
from logging.config import fileConfig

from alembic import context
from sqlalchemy import pool
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import async_engine_from_config

import src.account.infrastructure.notification.sent_email_model  # noqa: F401
import src.auth.infrastructure.persistence.credential_repository  # noqa: F401
import src.card.infrastructure.notification.sent_statement_email_model  # noqa: F401
import src.card.infrastructure.persistence.card_repository  # noqa: F401
import src.outbox.outbox_model  # noqa: F401
import src.payment.infrastructure.persistence.payment_repository  # noqa: F401
import src.payment.infrastructure.persistence.refund_repository  # noqa: F401
import src.task_queue.task_outbox_model  # noqa: F401

# The project's Base (and every model registered on it) must be imported for autogenerate
# to detect model changes — outbox_model/sent_email_model/card_repository/credential_repository/
# payment_repository/refund_repository/sent_statement_email_model/task_outbox_model also
# register onto the metadata by importing the same Base, so they are imported together
# here too (otherwise those tables end up in a state of "present in the models but not
# detected").
from src.account.infrastructure.persistence.account_repository import Base

# this is the Alembic Config object, which provides
# access to the values within the .ini file in use.
config = context.config

# Uses the same DATABASE_URL environment variable as the app, instead of alembic.ini's
# sqlalchemy.url — if the connection string existed separately in two places, it would risk
# drifting apart across deployment environments.
database_url = os.getenv("DATABASE_URL")
if database_url:
    config.set_main_option("sqlalchemy.url", database_url)

# Interpret the config file for Python logging.
# This line sets up loggers basically.
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = Base.metadata

# other values from the config, defined by the needs of env.py,
# can be acquired:
# my_important_option = config.get_main_option("my_important_option")
# ... etc.


def run_migrations_offline() -> None:
    """Run migrations in 'offline' mode.

    This configures the context with just a URL
    and not an Engine, though an Engine is acceptable
    here as well.  By skipping the Engine creation
    we don't even need a DBAPI to be available.

    Calls to context.execute() here emit the given string to the
    script output.

    """
    url = config.get_main_option("sqlalchemy.url")
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )

    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection: Connection) -> None:
    context.configure(connection=connection, target_metadata=target_metadata)

    with context.begin_transaction():
        context.run_migrations()


async def run_async_migrations() -> None:
    """In this scenario we need to create an Engine
    and associate a connection with the context.

    """

    connectable = async_engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )

    async with connectable.connect() as connection:
        await connection.run_sync(do_run_migrations)

    await connectable.dispose()


def run_migrations_online() -> None:
    """Run migrations in 'online' mode."""

    asyncio.run(run_async_migrations())


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
