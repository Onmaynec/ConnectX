# Third-party notices

ConnectX v0.2.0-alpha.2 builds its native userspace networking component from source.

## tun2socks

- Project: `xjasonlyu/tun2socks`
- Version: `v2.7.0`
- Commit: `8dda19e8e4613e014f0b12f3e624fdff5e5f23b3`
- License: MIT
- Copyright: 2019 Jason Lyu

The upstream MIT license text is included in `licenses/tun2socks-MIT.txt`.

## gVisor

- Project: `google/gvisor`
- Version: the exact module version selected by tun2socks v2.7.0 and recorded in the generated Go dependency lock/checksums
- License: Apache License 2.0, with additional per-file notices where applicable

The complete upstream license text is distributed with source and release notices. ConnectX does not claim ownership of tun2socks, gVisor, Go, Android NDK, or their dependencies.

## Build policy

ConnectX does not download or commit an opaque prebuilt tun2socks binary. CI resolves the locked source release, verifies Go module checksums, and builds Android shared libraries for the declared ABIs using the locked Go and Android NDK versions.
