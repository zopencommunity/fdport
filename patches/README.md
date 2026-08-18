# fd v10.3.0 z/OS Patches

Patches required to cross-compile [fd](https://github.com/sharkdp/fd) v10.3.0
for `s390x-ibm-zos` from a LoP (Linux on Power) machine.

> **Note:** fd v10.4.0+ requires rustc 1.87+ (`etcetera 0.11.0`).
> fd v10.3.0 is the newest version compatible with the 1.86-nightly cross toolchain.

## Prerequisites

The following patches from sibling directories must also be applied:

| Directory | What it patches |
|-----------|----------------|
| `../libc-zos/` | IBM libc fork — z/OS constants and declarations |
| `../memmap2-zos/` | No-op C stubs for `madvise`/`mlock`/`munlock` |

## Patches (apply in order)

### 1. `rustix-1.0.7.patch`

Adds z/OS support to `rustix`:

- **`backend/libc/c.rs`**: removes z/OS from `pread64`/`pwrite64`/`lstat64`/
  `stat64`/`posix_fallocate64` alias groups (z/OS has no `64`-suffixed variants);
  adds direct `pread`/`pwrite` for z/OS
- **`backend/libc/io/errno.rs`**: adds z/OS to exclusion lists for Linux-only
  error codes absent on z/OS (`ECHRNG`, `EL2HLT`, `EL2NSYNC`, `EL3HLT`,
  `EL3RST`, `ELNRNG`, `EUNATCH`, `ENOCSI`, `ERESTART`)
- **`backend/libc/io/syscalls.rs`**: excludes `preadv`/`pwritev` on z/OS
- **`io/dup.rs`**: excludes `dup3` on z/OS (no `dup3` syscall)
- **`io/read_write.rs`**: excludes public `preadv`/`pwritev` API on z/OS
- **`termios/types.rs`**: excludes constants absent on z/OS: `ECHOCTL`,
  `ECHOPRT`, `ECHOKE`, `IMAXBEL`, `B57600`, `B115200`, `B230400`,
  `VREPRINT`, `VLNEXT`, `VEOL2`, `VDSUSP`, `VSWTC`, `VDISCARD`, `VWERASE`
- **`ioctl/mod.rs`**: adds z/OS to the `_Opcode = c_int` group

### 2. `nix-0.30.1.patch`

Adds z/OS support to `nix`:

- **`features.rs`**: adds z/OS to `socket_atomic_cloexec() -> false` block
- **`errno.rs`**: fixes `errno_location()` to use `libc::__errno()`; cfg-gates
  Linux-only errno variants and their match arms out on z/OS
- **`sys/signal.rs`**: splits the AIX/z/OS `SIGNALS` array (z/OS has no
  `SIGPWR` or `SIGEMT`); removes z/OS from `SIGPWR`/`SIGEMT` enum variants
- **`sys/time.rs`**: adds `tv_usec_pad: 0` to all `timeval` initializers
  (z/OS `timeval` has an extra padding field)
- **`sys/stat.rs`**: cfg-gates `futimens` out on z/OS
- **`unistd.rs`**: cfg-gates `pw_passwd`/`pw_gecos` out in `User`, `gr_passwd`
  out in `Group`; cfg-gates `mkdtemp` out; fixes `sethostname` length type
- **`fcntl.rs`**: excludes `O_ASYNC` and `O_SEARCH` on z/OS

### 3. `errno-0.3.14.patch`

Adds `#[cfg_attr(target_os = "zos", link_name = "__errno")]` to the
`errno_location` extern fn declaration. Without this the symbol is unresolved
at link time (z/OS names it `__errno`, not `___errno`).

### 4. `ctrlc-3.4.7.patch`

Adds `target_os = "zos"` to the `pipe2(2)` fallback group so that
`ctrlc` uses `pipe(2)` + `fcntl(2)` instead of `pipe2(2)` (z/OS has no `pipe2`).

### 5. `clap-4.5.42.patch`

Works around a cargo 1.86 cross-compilation bug where optional proc-macro
dependencies (`clap_derive`) are not passed as `--extern` args to the
crate that uses them:

- **`Cargo.toml`**: makes `clap_derive` non-optional (removes `optional = true`)
- **`src/lib.rs`**: splits `pub use clap_derive::{self, ...}` into two `cfg`
  blocks to exclude the `self` re-export on z/OS (that re-export triggers the
  extern resolution failure)

A **two-phase build** is still required (see below).

### 6. `fd-10.3.0-cargo.patch`

Adds the `[patch.crates-io]` block to `Cargo.toml` pointing at all patched
crate directories:

```toml
[patch.crates-io]
libc    = { path = "/tmp/libc-zos" }
rustix  = { path = "/tmp/rustix-zos" }
nix     = { path = "/tmp/nix-zos" }
memmap2 = { path = "/tmp/memmap2-zos" }
ctrlc   = { path = "/tmp/ctrlc-zos" }
errno   = { path = "/tmp/errno-zos" }
clap    = { path = "/tmp/clap-zos" }
```

### 7. `fd-10.3.0-owner.patch`

Casts `uid.as_raw()` and `gid.as_raw()` to `u32` in `src/filter/owner.rs`.
z/OS defines `uid_t`/`gid_t` as `i32` but the `--owner` filter compares
against a `u32`; without the cast rustc rejects the type mismatch.

### 8. `fd-10.3.0-filetype.patch`

Fixes `--type f`, `--type d`, `--type l`, `--type e` (`--type empty`),
and `--extension` filters on z/OS.

**Root cause:** z/OS uses non-POSIX `st_mode` file-type bit positions:

| Type | POSIX | z/OS |
|------|-------|------|
| `S_IFREG` | `0x8000` | `0x03000000` |
| `S_IFDIR` | `0x4000` | `0x01000000` |
| `S_IFLNK` | `0xA000` | `0x05000000` |
| `S_IFBLK` | `0x6000` | `0x06000000` |
| `S_IFCHR` | `0x2000` | `0x02000000` |
| `S_IFSOCK`| `0xC000` | `0x07000000` |
| `S_IFIFO` | `0x1000` | `0x04000000` |
| `S_IFMT`  | `0xF000` | `0xFF000000` |

The pre-built Rust std for z/OS was compiled with the POSIX masks so
`FileType::is_file()` / `is_dir()` / `is_symlink()` all returned `false`.
z/OS also has no `d_type` field in `struct dirent`, so `ignore`'s walker
always calls `stat()` — which returns the correct `st_mode` — but the
result was then fed through the broken `FileType` wrapper.

**Fix:** adds a `zos_filetype` module to `src/filesystem.rs` with wrappers
for all seven file types that read `st_mode & S_IFMT` directly via
`MetadataExt::mode()` using the correct z/OS constants. Updates all call
sites in `filesystem.rs`, `filetypes.rs`, `output.rs`, `walk.rs`, and
`main.rs` to use the wrappers. On non-z/OS targets the wrappers are
zero-overhead `#[inline]` forwarders to the standard `FileType` methods.

## Applying the patches

```bash
REG=~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f
PDIR=/path/to/cross/patches

# Dependency crates
cp -r $REG/rustix-1.0.7  /tmp/rustix-zos  && patch -d /tmp/rustix-zos  -p1 < $PDIR/fd-zos/rustix-1.0.7.patch
cp -r $REG/nix-0.30.1    /tmp/nix-zos     && patch -d /tmp/nix-zos     -p1 < $PDIR/fd-zos/nix-0.30.1.patch
cp -r $REG/errno-0.3.14  /tmp/errno-zos   && patch -d /tmp/errno-zos   -p1 < $PDIR/fd-zos/errno-0.3.14.patch
cp -r $REG/ctrlc-3.4.7   /tmp/ctrlc-zos   && patch -d /tmp/ctrlc-zos   -p1 < $PDIR/fd-zos/ctrlc-3.4.7.patch
cp -r $REG/clap-4.5.42   /tmp/clap-zos    && patch -d /tmp/clap-zos    -p1 < $PDIR/fd-zos/clap-4.5.42.patch

# fd source
git clone --depth 1 --branch v10.3.0 https://github.com/sharkdp/fd /tmp/fd
patch -d /tmp/fd -p1 < $PDIR/fd-zos/fd-10.3.0-cargo.patch
patch -d /tmp/fd -p1 < $PDIR/fd-zos/fd-10.3.0-owner.patch
patch -d /tmp/fd -p1 < $PDIR/fd-zos/fd-10.3.0-filetype.patch
```

Also apply the libc and memmap2 patches from their own directories:

```bash
# IBM libc fork (requires git access to github.ibm.com:compiler/rust-libc)
git clone --branch zOS.0.2.169 git@github.ibm.com:compiler/rust-libc.git /tmp/libc-zos
patch -d /tmp/libc-zos -p1 < $PDIR/libc-zos/libc-zos.0.2.169.patch

# memmap2
cp -r $REG/memmap2-0.9.9 /tmp/memmap2-zos
patch -d /tmp/memmap2-zos -p1 < $PDIR/memmap2-zos/Cargo.toml.patch
# New files — apply as creation patches
patch -d /tmp/memmap2-zos -p0 < $PDIR/memmap2-zos/build.rs.patch
patch -d /tmp/memmap2-zos -p0 < $PDIR/memmap2-zos/zos_stubs.c.patch
```

## Building fd

### `.cargo/config.toml`

```toml
[target.s390x-ibm-zos]
linker = "/home/itodorov/rust_bld/toolchain/s390x-ibm-zos-cc"
ar     = "/home/itodorov/rust_bld/toolchain/s390x-ibm-zos-ar"

[env]
_BPXK_AUTOCVT  = "ON"
_CEE_RUNOPTS   = "FILETAG(AUTOCVT,AUTOTAG) POSIX(ON)"
```

### Two-phase build (works around cargo 1.86 proc-macro cross-compile bug)

```bash
RUSTC=/gsa/rtpgsa/projects/r/rustcross/v186/lop/rustcross/260610/usr/local/bin/rustc
CARGO=/gsa/rtpgsa/projects/r/rustcross/v186/lop/rustcross/260610/usr/local/bin/cargo
export PATH=/home/itodorov/rust-scripts/cross/venv/bin:$HOME/rust_bld/toolchain:$PATH
export CC_s390x_ibm_zos=/gsa/rtpgsa/projects/r/rustcross/v186/lop/toolchain/s390x-ibm-zos-cc
export CFLAGS_s390x_ibm_zos="--target=s390x-ibm-zos"
export AR_s390x_ibm_zos=/gsa/rtpgsa/projects/r/rustcross/v186/lop/llvm/bin/llvm-ar
export CROSS_SERVER_DOMAIN=zoscan2b.pok.stglabs.ibm.com
export CROSS_SERVER_PORT=5050
export CROSS_CLIENT_CACHEDIR=$HOME/rust_bld/rustcross/cache

cd /tmp/fd

# Phase 1: compile proc-macros (fails on clap but builds clap_derive .so)
RUSTC=$RUSTC $CARGO build --target s390x-ibm-zos --no-default-features --release 2>/dev/null || true

# Copy the proc-macro .so to a stable path before phase 2 clears it
SO=$(ls target/release/deps/libclap_derive-*.so | head -1)
cp "$SO" /tmp/libclap_derive_fd.so

# Phase 2: full build with proc-macro extern injected
export CARGO_TARGET_S390X_IBM_ZOS_RUSTFLAGS="--extern clap_derive=/tmp/libclap_derive_fd.so"
RUSTC=$RUSTC $CARGO build --target s390x-ibm-zos --no-default-features --release
```

Binary output: `/tmp/fd/target/s390x-ibm-zos/release/fd` (~18 MB)

## Test results (on zoscan2b.pok.stglabs.ibm.com)

All major features verified working:

| Feature | Status |
|---------|--------|
| Basic traversal | ✅ |
| `--type f` (regular files) | ✅ |
| `--type d` (directories) | ✅ |
| `--type l` (symlinks) | ✅ |
| `--type x` (executables) | ✅ |
| `--type e` / `--type empty` | ✅ |
| `--extension <ext>` | ✅ |
| `--type f --extension` combined | ✅ |
| Regex patterns | ✅ |
| `--glob` patterns | ✅ |
| `--max-depth` | ✅ |
| `--size +Nb` / `-Nb` | ✅ |
| `--ignore-case` / `--case-sensitive` | ✅ |
| `--no-ignore` | ✅ |
| `--owner` filter | ✅ (uid/gid cast fixed) |

## Known limitations

- **PCRE2**: fd uses the `regex` crate internally; there is no PCRE2 option,
  so this is a non-issue.
- **`--exec` / `--exec-batch`**: shell execution works; subprocess environment
  inherits `_BPXK_AUTOCVT=ON` from the caller.
- **Hidden files** (`.dotfiles`): `--hidden` works correctly; z/OS itself has
  no concept of hidden files beyond the leading-dot convention.
