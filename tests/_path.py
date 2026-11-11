"""Lets the tests import the host modules (host/ is not a package)."""
import os
import sys

HOST = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "host")
if HOST not in sys.path:
    sys.path.insert(0, HOST)
