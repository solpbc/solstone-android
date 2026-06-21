# PL Link Contract

The Android client implementation consumes PL QR links produced by the current solstone link service. The protocol source of truth lives with the link service and shared protocol repos; this repo should not silently fork the contract.

The validation app currently proves:

- direct `https://link.solpbc.org/p#...` QR parsing,
- on-device ECDSA P-256 key generation,
- PKCS#10 CSR creation,
- QR CA fingerprint pinning,
- certless TLS pair request,
- persisted client certificate bundle,
- mTLS reconnect,
- framed `GET /app/network/api/status`.

Production modules should turn those behaviors into host-testable parser and state-machine tests plus a device-gated Rogbid validation path.

