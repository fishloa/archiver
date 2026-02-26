#!/bin/sh
# Generates a fresh Apple client secret JWT on startup, then execs oauth2-proxy.
# The .p8 private key is passed as base64 in APPLE_KEY_P8_B64.
set -e

: "${APPLE_TEAM_ID:?missing}"
: "${APPLE_KEY_ID:?missing}"
: "${APPLE_CLIENT_ID:?missing}"
: "${APPLE_KEY_P8_B64:?missing — set to base64-encoded .p8 key content}"

# Decode the key to a temp file
echo "$APPLE_KEY_P8_B64" | base64 -d > /tmp/apple-key.p8

export OAUTH2_PROXY_CLIENT_SECRET=$(python3 -c "
import jwt, time
with open('/tmp/apple-key.p8') as f:
    key = f.read()
now = int(time.time())
print(jwt.encode({
    'iss': '$APPLE_TEAM_ID',
    'iat': now,
    'exp': now + 86400 * 150,
    'aud': 'https://appleid.apple.com',
    'sub': '$APPLE_CLIENT_ID',
}, key, algorithm='ES256', headers={'kid': '$APPLE_KEY_ID'}))
")

rm -f /tmp/apple-key.p8

echo "Generated Apple client secret (valid ~5 months from now)"
exec /bin/oauth2-proxy "$@"
