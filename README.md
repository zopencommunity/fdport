[![Automatic version updates](https://github.com/zopencommunity/fdport/actions/workflows/bump.yml/badge.svg)](https://github.com/ZOSOpenTools/fdport/actions/workflows/bump.yml)

# fd

A simple, fast and user-friendly alternative to `find`. Written in Rust.

Upstream project: https://github.com/sharkdp/fd

# Installation and Usage

Use the zopen package manager ([QuickStart Guide](https://zopen.community/#/Guides/QuickStart)) to install:
```bash
zopen install fd
```

Then use it like `find`:
```bash
fd <pattern> [path]
fd '\.rs$'           # find all Rust files
fd -t f -e txt       # find regular files with .txt extension
fd -t d lib          # find directories named lib
```

# How this port was built

`fd` is written in Rust. Because the Rust toolchain is not yet natively available
on z/OS, this port was **cross-compiled** on a Linux-on-Power (LoP, ppc64le) host using an
IBM-internal Rust cross-compilation toolchain targeting `s390x-ibm-zos`.

The cross-compilation infrastructure and all required patches to upstream Rust
crates are maintained at:

  https://github.ibm.com/compiler/rust-scripts (branch `itodorov/zos-cross-compile-setup`)

The patches cover the following crates that required z/OS-specific fixes:
- `libc` — z/OS `struct dirent`, `termios`, `fcntl` constants, missing symbols
- `rustix` — z/OS `errno` codes, termios flags, missing syscalls
- `nix` — z/OS signal numbers, `errno` variants, `unistd` APIs
- `memmap2` — no-op C stubs for `madvise`/`mlock`/`munlock` (absent on z/OS)
- `fd` itself — z/OS `S_IFMT` file type constants differ from POSIX

The host machine used for cross-compilation is a Linux on Power (ppc64le) system.

The resulting binary is statically linked against the Rust standard library
and dynamically linked against the z/OS system libraries (`libc.a`, `libzoslib.so`).

# Troubleshooting

- Files must be ASCII-tagged for fd to search them correctly. Use `chtag -tc ISO8859-1 <file>` or `chtag -Rtc ISO8859-1 <dir>`.
- Symlinks are not followed by default; use `-L` / `--follow` to follow them.

# Contributing
Contributions are welcome! Please follow the [zopen contribution guidelines](https://github.com/zopencommunity/meta/blob/main/CONTRIBUTING.md).
