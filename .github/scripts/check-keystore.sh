#!/usr/bin/env bash
# Verifies the release signing secrets and decodes the keystore to $1.
# Writes "file=<path>" to $GITHUB_OUTPUT. Never prints secret values.
set -uo pipefail
target="$1"

# ANDROID_KEY_PASSWORD is optional: PKCS12 keystores (the default) use the keystore password for the key.
if [ -z "${ANDROID_KEY_PASSWORD:-}" ]; then
  ANDROID_KEY_PASSWORD="$ANDROID_KEYSTORE_PASSWORD"
  key_password_note=" (key password = keystore password)"
fi

missing=""
for name in KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS; do
  [ -z "${!name:-}" ] && missing="$missing $name"
done
if [ -n "$missing" ]; then
  missing="${missing/KEYSTORE_BASE64/ANDROID_KEYSTORE_BASE64}"
  echo "::error title=Signing::Missing repository secrets:$missing"
  exit 1
fi

# Tolerate line breaks and spaces from copy & paste.
if ! printf '%s' "$KEYSTORE_BASE64" | tr -d ' \r\n\t' | base64 --decode > "$target" 2>/dev/null; then
  echo "::error title=Signing::ANDROID_KEYSTORE_BASE64 is not valid base64. Create it with: base64 -w0 release.jks (Linux) or [Convert]::ToBase64String([IO.File]::ReadAllBytes('release.jks')) (PowerShell)."
  exit 1
fi
size=$(stat -c %s "$target")

if ! out=$(keytool -list -keystore "$target" -storepass "$ANDROID_KEYSTORE_PASSWORD" 2>&1); then
  reason=$(printf '%s' "$out" | grep -m1 -iE 'keytool error|exception' | sed 's/^keytool error: //')
  echo "::error title=Signing::The keystore ($size bytes after decoding) cannot be opened: ${reason:-unknown error}. Usually ANDROID_KEYSTORE_PASSWORD is wrong or ANDROID_KEYSTORE_BASE64 is not the encoded .jks/.keystore file."
  exit 1
fi

aliases=$(printf '%s' "$out" | grep -iE 'PrivateKeyEntry' | cut -d, -f1 | paste -sd ',' -)
if ! keytool -list -keystore "$target" -storepass "$ANDROID_KEYSTORE_PASSWORD" -alias "$ANDROID_KEY_ALIAS" > /dev/null 2>&1; then
  echo "::error title=Signing::Alias given in ANDROID_KEY_ALIAS not found in the keystore. Aliases in the keystore: ${aliases:-none}"
  exit 1
fi

# Generating a certificate request needs the private key, so it fails with a wrong key password.
if ! keytool -certreq -keystore "$target" -storepass "$ANDROID_KEYSTORE_PASSWORD" -alias "$ANDROID_KEY_ALIAS" \
     -keypass "$ANDROID_KEY_PASSWORD" -file /dev/null > /dev/null 2>&1; then
  if [ -n "${key_password_note:-}" ]; then
    echo "::error title=Signing::The key has its own password. Add it as the ANDROID_KEY_PASSWORD secret."
  else
    echo "::error title=Signing::ANDROID_KEY_PASSWORD is wrong for the alias in ANDROID_KEY_ALIAS. Remove the secret if the key uses the keystore password."
  fi
  exit 1
fi

echo "::notice title=Signing::Keystore OK ($size bytes, alias found, passwords correct${key_password_note:-})."
echo "file=$target" >> "$GITHUB_OUTPUT"
