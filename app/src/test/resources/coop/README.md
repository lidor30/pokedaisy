# Co-op test vectors

`noise-ik-25519-aesgcm-sha256.json` is the one object with `protocol_name` `Noise_IK_25519_AESGCM_SHA256`
(no PSK) from the cacophony project's `vectors/cacophony.txt`
(https://github.com/haskell-cryptography/cacophony, public-domain / Unlicense test data), copied as it is
on 2026-10-25. `CoopNoiseTest` drives our handshake with its keys and checks every byte.
