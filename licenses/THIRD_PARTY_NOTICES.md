# Third-party notices

ConnectX v0.2.0-alpha.2 builds its native userspace networking component from source.

## tun2socks

- Project: `xjasonlyu/tun2socks`
- Version: `v2.7.0`
- Commit: `8dda19e8e4613e014f0b12f3e624fdff5e5f23b3`
- License: MIT
- Copyright: 2019 Jason Lyu

The upstream MIT license text is included in `tun2socks-MIT.txt`.

## gVisor

- Project: `google/gvisor`
- Module version: `v0.0.0-20260701204157-69c2d17aea96`
- Commit: `69c2d17aea96`
- License: Apache License 2.0, with additional MIT/BSD notices for designated files

The upstream combined license text is included in `gvisor-LICENSE.txt`.

ConnectX does not claim ownership of tun2socks, gVisor, Go, Android NDK, or their dependencies.

## Build policy

ConnectX does not download or commit an opaque prebuilt tun2socks binary. CI verifies the locked tun2socks tag against its audited commit, downloads only the module versions pinned in `go.mod`, verifies Go module checksums, checks the exact Go and Android NDK toolchains, and builds Android shared libraries for the declared ABIs.
