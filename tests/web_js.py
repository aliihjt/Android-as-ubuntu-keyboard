"""Prints the JavaScript inside host/web/index.html, so CI can syntax-check it with node."""
import os
import re

page = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "host", "web", "index.html")
with open(page, encoding="utf-8") as f:
    print("\n".join(re.findall(r"<script[^>]*>(.*?)</script>", f.read(), re.S)))
