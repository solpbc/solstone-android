# PL Link Contract

The Android client implementation consumes PL QR links produced by the current solstone link service. The protocol source of truth lives with the link service and shared protocol repos; this repo should not silently fork the contract.

The validation app currently proves:

- direct `https://go.solstone.app/p#...` QR parsing,
- on-device ECDSA P-256 key generation,
- PKCS#10 CSR creation,
- QR CA fingerprint pinning,
- certless TLS pair request,
- persisted client certificate bundle,
- mTLS reconnect,
- framed `GET /app/network/api/status`.

Production modules should turn those behaviors into host-testable parser and state-machine tests plus a device-gated Rogbid validation path.

## Direct payloads

The decoded direct payload has one of two forms:

- `0x04` is a 40-byte single-candidate payload: version bytes, one IPv4
  address, a big-endian port, a 16-byte nonce, and a 16-byte CA fingerprint
  prefix.
- `0x05` is a `37 + 4N` byte multi-candidate payload, where `N` is in `1..4`.
  It carries one shared big-endian port, `N` IPv4 addresses in encoded order,
  a 16-byte nonce, and a 16-byte CA fingerprint prefix.

Direct candidates are admitted only from `10/8`, `172.16/12`, `192.168/16`,
`169.254/16`, `100.64/10`, and `127/8`. A disallowed member refuses the whole
link; members are never silently filtered. Repeated candidates are coalesced
by first occurrence before subnet ordering.

Session-open failures may advance through the ordered candidates. Invoking the
first nonce-bearing pair request commits the attempt: every response or failure
after that request is terminal and no later candidate is dialed. The Rogbid
hardware probe shares this parser contract but dials only the first parsed
candidate.
