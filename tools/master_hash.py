"""Prints masterSalt/masterHash lines for local.properties (same scheme as PasswordStore: PBKDF2-HMAC-SHA256)."""
import base64
import getpass
import hashlib
import os

password = getpass.getpass("Master password: ")
if password != getpass.getpass("Repeat: "):
    raise SystemExit("The passwords do not match.")
salt = os.urandom(16)
digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 120_000, 32)
print("masterSalt=" + base64.b64encode(salt).decode())
print("masterHash=" + base64.b64encode(digest).decode())
