# A fixture that intentionally has no outbox_writer.py/outbox_poller.py/outbox_consumer.py
# — verifies that the shared-infra rule checks for the actual files' existence, rather
# than passing on the mere existence of the outbox/ directory.
