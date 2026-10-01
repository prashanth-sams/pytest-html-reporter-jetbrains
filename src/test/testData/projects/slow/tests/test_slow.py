import os
import signal
import time

# A process started in the background inherits SIGINT as "ignored", and Python then never raises KeyboardInterrupt.
# The interrupt test wants the normal interactive behaviour; the kill test asks for the ignored one.
if os.environ.get("PHR_IGNORE_SIGINT"):
    signal.signal(signal.SIGINT, signal.SIG_IGN)
else:
    signal.signal(signal.SIGINT, signal.default_int_handler)


def test_quick():
    assert True


def test_slow():
    # Long enough to be stopped part-way through by the cancellation tests.
    time.sleep(120)
