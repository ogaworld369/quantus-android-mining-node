# Third-party licences

This app bundles two prebuilt Rust binaries. Their dependencies are
listed below, per binary, exactly as locked for the shipped builds
(the `cargo tree` closure of each binary for
`aarch64-linux-android`, normal and build dependencies). The lists
were generated from the two projects' `Cargo.lock` files; the
licence of each crate is the one its own manifest declares.

The app's own code is Apache-2.0 (see the root `LICENSE`).

## The node binary (`libquantus_node.so`)

Built from the upstream Quantus chain
([Quantus-Network/chain](https://github.com/Quantus-Network/chain))
at the pinned source described in
[BUILD-android.md](BUILD-android.md), with the patches in
[`patches/`](../patches/) applied. One patched crate is
`client/executor-wasmtime` (GPL-3.0-or-later WITH
Classpath-exception-2.0); the corresponding source is the upstream
repository at the pinned revision plus that patch, and the build
commands in BUILD-android.md reproduce the binary from it.

Most crates below are MIT and/or Apache-2.0. The copyleft entries
are: the Substrate client crates (GPL-3.0-or-later WITH
Classpath-exception-2.0), `qp-rusty-crystals-dilithium` and
`qp-rusty-crystals-hdwallet` (GPL-3.0), `proc-macro-warning`
(GPL-3.0 OR Apache-2.0), `array-bytes` (declared
"Apache-2.0/GPL-3.0") and `option-ext` (MPL-2.0).

## The miner binary (`libquantus_miner.so`)

Bundled since v4.0.0. Built from
[Quantus-Network/quantus-miner](https://github.com/Quantus-Network/quantus-miner)
as described in [BUILD-miner.md](BUILD-miner.md); that project is
Apache-2.0, and its own crates below are covered by its LICENSE.

### Node dependency list (804 crates)

| Crate | Version | Licence |
|---|---|---|
| Inflector | 0.11.4 | BSD-2-Clause |
| addr2line | 0.24.2 | Apache-2.0 OR MIT |
| addr2line | 0.25.1 | Apache-2.0 OR MIT |
| adler2 | 2.0.1 | 0BSD OR MIT OR Apache-2.0 |
| aead | 0.5.2 | MIT OR Apache-2.0 |
| aes | 0.8.4 | MIT OR Apache-2.0 |
| aes-gcm | 0.10.3 | Apache-2.0 OR MIT |
| ahash | 0.7.8 | MIT OR Apache-2.0 |
| ahash | 0.8.12 | MIT OR Apache-2.0 |
| aho-corasick | 1.1.3 | Unlicense OR MIT |
| allocator-api2 | 0.2.21 | MIT OR Apache-2.0 |
| android_system_properties | 0.1.5 | MIT/Apache-2.0 |
| anstream | 1.0.0 | MIT OR Apache-2.0 |
| anstyle | 1.0.14 | MIT OR Apache-2.0 |
| anstyle-parse | 1.0.0 | MIT OR Apache-2.0 |
| anstyle-query | 1.1.4 | MIT OR Apache-2.0 |
| anyhow | 1.0.104 | MIT OR Apache-2.0 |
| approx | 0.5.1 | Apache-2.0 |
| aquamarine | 0.5.0 | MIT |
| arbitrary | 1.4.2 | MIT OR Apache-2.0 |
| array-bytes | 6.2.3 | Apache-2.0/GPL-3.0 |
| arrayref | 0.3.9 | BSD-2-Clause |
| arrayvec | 0.7.6 | MIT OR Apache-2.0 |
| asn1-rs | 0.7.1 | MIT OR Apache-2.0 |
| asn1-rs-derive | 0.6.0 | MIT OR Apache-2.0 |
| asn1-rs-impl | 0.2.0 | MIT/Apache-2.0 |
| async-channel | 1.9.0 | Apache-2.0 OR MIT |
| async-trait | 0.1.89 | MIT OR Apache-2.0 |
| asynchronous-codec | 0.6.2 | MIT |
| asynchronous-codec | 0.7.0 | MIT |
| atomic-waker | 1.1.2 | Apache-2.0 OR MIT |
| autocfg | 1.5.0 | Apache-2.0 OR MIT |
| backtrace | 0.3.75 | MIT OR Apache-2.0 |
| base-x | 0.2.11 | MIT |
| base16ct | 0.2.0 | Apache-2.0 OR MIT |
| base64 | 0.22.1 | MIT OR Apache-2.0 |
| base64ct | 1.8.0 | Apache-2.0 OR MIT |
| binary-merkle-tree | 16.1.1 | Apache-2.0 |
| bindgen | 0.72.1 | BSD-3-Clause |
| bip39 | 2.2.2 | CC0-1.0 |
| bitcoin_hashes | 0.14.0 | CC0-1.0 |
| bitflags | 1.3.2 | MIT/Apache-2.0 |
| bitflags | 2.9.4 | MIT OR Apache-2.0 |
| blake2 | 0.10.6 | MIT OR Apache-2.0 |
| blake2 | 0.8.1 | MIT OR Apache-2.0 |
| blake2b_simd | 1.0.3 | MIT |
| block-buffer | 0.10.4 | MIT OR Apache-2.0 |
| block-buffer | 0.12.1 | MIT OR Apache-2.0 |
| block-buffer | 0.9.0 | MIT OR Apache-2.0 |
| bounded-collections | 0.3.2 | MIT OR Apache-2.0 |
| bs58 | 0.5.1 | MIT/Apache-2.0 |
| build-helper | 0.1.1 | MIT/Apache-2.0 |
| bumpalo | 3.19.0 | MIT OR Apache-2.0 |
| byte-slice-cast | 1.2.3 | MIT |
| byte-tools | 0.3.1 | MIT OR Apache-2.0 |
| bytemuck | 1.23.2 | Zlib OR Apache-2.0 OR MIT |
| byteorder | 1.5.0 | Unlicense OR MIT |
| bytes | 1.11.1 | MIT |
| c2-chacha | 0.3.3 | MIT/Apache-2.0 |
| camino | 1.2.0 | MIT OR Apache-2.0 |
| cargo-platform | 0.1.9 | MIT OR Apache-2.0 |
| cargo_metadata | 0.15.4 | MIT |
| cc | 1.2.38 | MIT OR Apache-2.0 |
| cexpr | 0.6.0 | Apache-2.0/MIT |
| cfg-expr | 0.15.8 | MIT OR Apache-2.0 |
| cfg-if | 1.0.3 | MIT OR Apache-2.0 |
| cfg_aliases | 0.1.1 | MIT |
| cfg_aliases | 0.2.1 | MIT |
| chacha | 0.3.0 | MIT/Apache-2.0 |
| chacha20 | 0.10.0 | MIT OR Apache-2.0 |
| chacha20 | 0.9.1 | Apache-2.0 OR MIT |
| chacha20poly1305 | 0.10.1 | Apache-2.0 OR MIT |
| chrono | 0.4.42 | MIT OR Apache-2.0 |
| cid | 0.11.3 | MIT |
| cipher | 0.2.5 | MIT OR Apache-2.0 |
| cipher | 0.4.4 | MIT OR Apache-2.0 |
| clang-sys | 1.8.1 | Apache-2.0 |
| clap | 4.6.1 | MIT OR Apache-2.0 |
| clap_builder | 4.6.0 | MIT OR Apache-2.0 |
| clap_derive | 4.6.1 | MIT OR Apache-2.0 |
| clap_lex | 1.1.0 | MIT OR Apache-2.0 |
| clatter | 2.2.0 | MIT |
| cobs | 0.3.0 | MIT OR Apache-2.0 |
| codespan-reporting | 0.13.1 | Apache-2.0 |
| colorchoice | 1.0.4 | MIT OR Apache-2.0 |
| combine | 4.6.7 | MIT |
| common-path | 1.0.0 | MIT/Apache-2.0 |
| concurrent-queue | 2.5.0 | Apache-2.0 OR MIT |
| console | 0.15.11 | MIT |
| const-hex | 1.16.0 | MIT OR Apache-2.0 |
| const-oid | 0.9.6 | Apache-2.0 OR MIT |
| const-random | 0.1.18 | MIT OR Apache-2.0 |
| const-random-macro | 0.1.16 | MIT OR Apache-2.0 |
| const_format | 0.2.34 | Zlib |
| const_format_proc_macros | 0.2.34 | Zlib |
| constant_time_eq | 0.3.1 | CC0-1.0 OR MIT-0 OR Apache-2.0 |
| convert_case | 0.4.0 | MIT |
| convert_case | 0.6.0 | MIT |
| cpp_demangle | 0.4.5 | MIT OR Apache-2.0 |
| cpufeatures | 0.2.17 | MIT OR Apache-2.0 |
| cpufeatures | 0.3.0 | MIT OR Apache-2.0 |
| cranelift-assembler-x64 | 0.123.20 | Apache-2.0 WITH LLVM-exception |
| cranelift-assembler-x64-meta | 0.123.20 | Apache-2.0 WITH LLVM-exception |
| cranelift-bforest | 0.123.20 | Apache-2.0 WITH LLVM-exception |
| cranelift-bitset | 0.123.20 | Apache-2.0 WITH LLVM-exception |
| cranelift-codegen | 0.123.20 | Apache-2.0 WITH LLVM-exception |
| cranelift-codegen-meta | 0.123.20 | Apache-2.0 WITH LLVM-exception |
| cranelift-codegen-shared | 0.123.20 | Apache-2.0 WITH LLVM-exception |
| cranelift-control | 0.123.20 | Apache-2.0 WITH LLVM-exception |
| cranelift-entity | 0.123.20 | Apache-2.0 WITH LLVM-exception |
| cranelift-frontend | 0.123.20 | Apache-2.0 WITH LLVM-exception |
| cranelift-isle | 0.123.20 | Apache-2.0 WITH LLVM-exception |
| cranelift-native | 0.123.20 | Apache-2.0 WITH LLVM-exception |
| cranelift-srcgen | 0.123.20 | Apache-2.0 WITH LLVM-exception |
| crc32fast | 1.5.0 | MIT OR Apache-2.0 |
| critical-section | 1.2.0 | MIT OR Apache-2.0 |
| crossbeam-channel | 0.5.15 | MIT OR Apache-2.0 |
| crossbeam-deque | 0.8.6 | MIT OR Apache-2.0 |
| crossbeam-epoch | 0.9.20 | MIT OR Apache-2.0 |
| crossbeam-utils | 0.8.21 | MIT OR Apache-2.0 |
| crunchy | 0.2.4 | MIT |
| crypto-bigint | 0.5.5 | Apache-2.0 OR MIT |
| crypto-common | 0.1.6 | MIT OR Apache-2.0 |
| crypto-common | 0.2.2 | MIT OR Apache-2.0 |
| crypto-mac | 0.7.0 | MIT OR Apache-2.0 |
| ctr | 0.9.2 | MIT OR Apache-2.0 |
| curve25519-dalek | 4.1.3 | BSD-3-Clause |
| cxx | 1.0.198 | MIT OR Apache-2.0 |
| cxx-build | 1.0.198 | MIT OR Apache-2.0 |
| cxxbridge-flags | 1.0.198 | MIT OR Apache-2.0 |
| cxxbridge-macro | 1.0.198 | MIT OR Apache-2.0 |
| darling | 0.23.0 | MIT |
| darling_core | 0.23.0 | MIT |
| darling_macro | 0.23.0 | MIT |
| dashmap | 5.5.3 | MIT |
| data-encoding | 2.9.0 | MIT |
| data-encoding-macro | 0.1.18 | MIT |
| data-encoding-macro-internal | 0.1.16 | MIT |
| debugid | 0.8.0 | Apache-2.0 |
| der | 0.7.10 | Apache-2.0 OR MIT |
| der-parser | 10.0.0 | MIT OR Apache-2.0 |
| deranged | 0.5.3 | MIT OR Apache-2.0 |
| derive-syn-parse | 0.2.0 | MIT OR Apache-2.0 |
| derive_more | 0.99.20 | MIT |
| derive_more | 1.0.0 | MIT |
| derive_more-impl | 1.0.0 | MIT |
| digest | 0.10.7 | MIT OR Apache-2.0 |
| digest | 0.11.3 | MIT OR Apache-2.0 |
| digest | 0.8.1 | MIT OR Apache-2.0 |
| digest | 0.9.0 | MIT OR Apache-2.0 |
| directories | 5.0.1 | MIT OR Apache-2.0 |
| directories-next | 2.0.0 | MIT OR Apache-2.0 |
| dirs | 5.0.1 | MIT OR Apache-2.0 |
| dirs-sys | 0.4.1 | MIT OR Apache-2.0 |
| dirs-sys-next | 0.1.2 | MIT OR Apache-2.0 |
| displaydoc | 0.2.5 | MIT OR Apache-2.0 |
| docify | 0.2.9 | MIT |
| docify_macros | 0.2.9 | MIT |
| downcast | 0.11.0 | MIT |
| dyn-clone | 1.0.20 | MIT OR Apache-2.0 |
| ecdsa | 0.16.9 | Apache-2.0 OR MIT |
| ed25519 | 2.2.3 | Apache-2.0 OR MIT |
| ed25519-dalek | 2.2.0 | BSD-3-Clause |
| ed25519-zebra | 4.1.0 | MIT OR Apache-2.0 |
| either | 1.15.0 | MIT OR Apache-2.0 |
| elliptic-curve | 0.13.8 | Apache-2.0 OR MIT |
| enum-display | 0.1.4 | MIT |
| enum-display-macro | 0.1.4 | MIT |
| environmental | 1.1.4 | Apache-2.0 |
| equivalent | 1.0.2 | Apache-2.0 OR MIT |
| errno | 0.3.14 | MIT OR Apache-2.0 |
| event-listener | 2.5.3 | Apache-2.0 OR MIT |
| exit-future | 0.2.0 | MIT |
| expander | 2.2.1 | MIT OR Apache-2.0 |
| fastbloom | 0.17.0 | MIT OR Apache-2.0 |
| fastrand | 2.3.0 | Apache-2.0 OR MIT |
| fdlimit | 0.3.0 | Apache-2.0 |
| ff | 0.13.1 | MIT/Apache-2.0 |
| file-guard | 0.2.0 | MIT |
| filetime | 0.2.26 | MIT/Apache-2.0 |
| finality-grandpa | 0.16.3 | Apache-2.0 |
| find-msvc-tools | 0.1.2 | MIT OR Apache-2.0 |
| fixed-hash | 0.7.0 | MIT OR Apache-2.0 |
| fixed-hash | 0.8.0 | MIT OR Apache-2.0 |
| fixedbitset | 0.4.2 | MIT/Apache-2.0 |
| fixedbitset | 0.5.7 | MIT OR Apache-2.0 |
| fnv | 1.0.7 | Apache-2.0 / MIT |
| foldhash | 0.1.5 | Zlib |
| foldhash | 0.2.0 | Zlib |
| fork-tree | 13.0.1 | Apache-2.0 |
| form_urlencoded | 1.2.2 | MIT OR Apache-2.0 |
| forwarded-header-value | 0.1.1 | ISC |
| fragile | 2.0.1 | Apache-2.0 |
| frame-benchmarking | 45.0.3 | Apache-2.0 |
| frame-executive | 45.0.1 | Apache-2.0 |
| frame-metadata | 23.0.1 | Apache-2.0 |
| frame-metadata-hash-extension | 0.13.0 | Apache-2.0 |
| frame-support | 45.1.0 | Apache-2.0 |
| frame-support-procedural | 36.0.0 | Apache-2.0 |
| frame-support-procedural-tools | 13.0.1 | Apache-2.0 |
| frame-support-procedural-tools-derive | 12.0.0 | Apache-2.0 |
| frame-system | 45.0.0 | Apache-2.0 |
| frame-system-rpc-runtime-api | 40.0.0 | Apache-2.0 |
| frame-try-runtime | 0.51.0 | Apache-2.0 |
| fs-err | 2.11.0 | MIT/Apache-2.0 |
| fs2 | 0.4.3 | MIT/Apache-2.0 |
| futures | 0.3.31 | MIT OR Apache-2.0 |
| futures-channel | 0.3.31 | MIT OR Apache-2.0 |
| futures-core | 0.3.31 | MIT OR Apache-2.0 |
| futures-executor | 0.3.31 | MIT OR Apache-2.0 |
| futures-io | 0.3.31 | MIT OR Apache-2.0 |
| futures-macro | 0.3.31 | MIT OR Apache-2.0 |
| futures-sink | 0.3.31 | MIT OR Apache-2.0 |
| futures-task | 0.3.31 | MIT OR Apache-2.0 |
| futures-timer | 3.0.3 | MIT/Apache-2.0 |
| futures-util | 0.3.31 | MIT OR Apache-2.0 |
| fxhash | 0.2.1 | Apache-2.0/MIT |
| fxprof-processed-profile | 0.6.0 | MIT OR Apache-2.0 |
| generic-array | 0.12.4 | MIT |
| generic-array | 0.14.7 | MIT |
| getrandom | 0.2.17 | MIT OR Apache-2.0 |
| getrandom | 0.3.3 | MIT OR Apache-2.0 |
| getrandom | 0.4.2 | MIT OR Apache-2.0 |
| getrandom_or_panic | 0.0.3 | BSD-3-Clause |
| ghash | 0.5.1 | Apache-2.0 OR MIT |
| gimli | 0.31.1 | MIT OR Apache-2.0 |
| gimli | 0.32.3 | MIT OR Apache-2.0 |
| glob | 0.3.3 | MIT OR Apache-2.0 |
| governor | 0.6.3 | MIT |
| group | 0.13.0 | MIT/Apache-2.0 |
| h2 | 0.4.20 | MIT |
| hash-db | 0.16.0 | Apache-2.0 |
| hash256-std-hasher | 0.15.2 | Apache-2.0 |
| hashbrown | 0.12.3 | MIT OR Apache-2.0 |
| hashbrown | 0.13.2 | MIT OR Apache-2.0 |
| hashbrown | 0.14.5 | MIT OR Apache-2.0 |
| hashbrown | 0.15.5 | MIT OR Apache-2.0 |
| hashlink | 0.8.4 | MIT OR Apache-2.0 |
| heck | 0.4.1 | MIT OR Apache-2.0 |
| heck | 0.5.0 | MIT OR Apache-2.0 |
| hex | 0.4.3 | MIT OR Apache-2.0 |
| hex-conservative | 0.2.1 | CC0-1.0 |
| hex-literal | 0.4.1 | MIT OR Apache-2.0 |
| hickory-net | 0.26.2 | MIT OR Apache-2.0 |
| hickory-proto | 0.26.2 | MIT OR Apache-2.0 |
| hickory-resolver | 0.26.1 | MIT OR Apache-2.0 |
| hkdf | 0.12.4 | MIT OR Apache-2.0 |
| hmac | 0.12.1 | MIT OR Apache-2.0 |
| http | 1.3.1 | MIT OR Apache-2.0 |
| http-body | 1.0.1 | MIT |
| http-body-util | 0.1.3 | MIT |
| httparse | 1.10.1 | MIT OR Apache-2.0 |
| httpdate | 1.0.3 | MIT OR Apache-2.0 |
| hybrid-array | 0.2.3 | MIT OR Apache-2.0 |
| hybrid-array | 0.4.14 | MIT OR Apache-2.0 |
| hyper | 1.7.0 | MIT |
| hyper-rustls | 0.27.7 | Apache-2.0 OR ISC OR MIT |
| hyper-util | 0.1.17 | MIT |
| iana-time-zone | 0.1.64 | MIT OR Apache-2.0 |
| icu_collections | 2.0.0 | Unicode-3.0 |
| icu_locale_core | 2.0.0 | Unicode-3.0 |
| icu_normalizer | 2.0.0 | Unicode-3.0 |
| icu_normalizer_data | 2.0.0 | Unicode-3.0 |
| icu_properties | 2.0.1 | Unicode-3.0 |
| icu_properties_data | 2.0.1 | Unicode-3.0 |
| icu_provider | 2.0.0 | Unicode-3.0 |
| ident_case | 1.0.1 | MIT/Apache-2.0 |
| idna | 1.1.0 | MIT OR Apache-2.0 |
| idna_adapter | 1.2.1 | Apache-2.0 OR MIT |
| impl-codec | 0.7.1 | MIT OR Apache-2.0 |
| impl-num-traits | 0.2.0 | MIT OR Apache-2.0 |
| impl-serde | 0.5.0 | MIT OR Apache-2.0 |
| impl-trait-for-tuples | 0.2.3 | Apache-2.0/MIT |
| include_dir | 0.7.4 | MIT |
| include_dir_macros | 0.7.4 | MIT |
| indexmap | 2.11.4 | Apache-2.0 OR MIT |
| inout | 0.1.4 | MIT OR Apache-2.0 |
| instant | 0.1.13 | BSD-3-Clause |
| integer-sqrt | 0.1.5 | Apache-2.0/MIT |
| ip_network | 0.4.1 | BSD-2-Clause |
| ipnet | 2.11.0 | MIT OR Apache-2.0 |
| is-terminal | 0.4.16 | MIT |
| is_terminal_polyfill | 1.70.1 | MIT OR Apache-2.0 |
| itertools | 0.10.5 | MIT/Apache-2.0 |
| itertools | 0.11.0 | MIT OR Apache-2.0 |
| itertools | 0.12.1 | MIT OR Apache-2.0 |
| itertools | 0.13.0 | MIT OR Apache-2.0 |
| itertools | 0.14.0 | MIT OR Apache-2.0 |
| itoa | 1.0.15 | MIT OR Apache-2.0 |
| jam-codec | 0.1.1 | Apache-2.0 |
| jam-codec-derive | 0.1.1 | Apache-2.0 |
| jni | 0.22.4 | MIT OR Apache-2.0 |
| jni-macros | 0.22.4 | MIT OR Apache-2.0 |
| jni-sys | 0.4.1 | MIT OR Apache-2.0 |
| jni-sys-macros | 0.4.1 | MIT OR Apache-2.0 |
| jobserver | 0.1.34 | MIT OR Apache-2.0 |
| jsonrpsee | 0.24.10 | MIT |
| jsonrpsee-core | 0.24.10 | MIT |
| jsonrpsee-proc-macros | 0.24.10 | MIT |
| jsonrpsee-server | 0.24.10 | MIT |
| jsonrpsee-types | 0.24.10 | MIT |
| k256 | 0.13.4 | Apache-2.0 OR MIT |
| keccak | 0.1.6 | Apache-2.0 OR MIT |
| keccak-hash | 0.8.0 | MIT OR Apache-2.0 |
| kem | 0.3.0-pre.0 | Apache-2.0 OR MIT |
| keystream | 1.0.0 | MIT/Apache-2.0 |
| kvdb | 0.13.0 | MIT OR Apache-2.0 |
| kvdb-memorydb | 0.13.0 | MIT OR Apache-2.0 |
| kvdb-rocksdb | 0.21.0 | MIT OR Apache-2.0 |
| lazy_static | 1.5.0 | MIT OR Apache-2.0 |
| leb128fmt | 0.1.0 | MIT OR Apache-2.0 |
| libc | 0.2.191 | MIT OR Apache-2.0 |
| libm | 0.2.15 | MIT |
| libp2p-identity | 0.2.14 | MIT |
| librocksdb-sys | 0.17.3+10.4.2 | MIT/Apache-2.0/BSD-3-Clause |
| libsecp256k1 | 0.7.2 | Apache-2.0 |
| libsecp256k1-core | 0.3.0 | Apache-2.0 |
| libsecp256k1-gen-ecmult | 0.3.0 | Apache-2.0 |
| libsecp256k1-gen-genmult | 0.3.0 | Apache-2.0 |
| link-cplusplus | 1.0.12 | MIT OR Apache-2.0 |
| linked-hash-map | 0.5.6 | MIT/Apache-2.0 |
| linked_hash_set | 0.1.5 | Apache-2.0 |
| linregress | 0.5.4 | MIT |
| linux-raw-sys | 0.12.1 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| lioness | 0.1.2 | MIT/Apache-2.0 |
| litemap | 0.8.0 | Unicode-3.0 |
| litep2p | 0.13.3 | MIT |
| lock_api | 0.4.13 | MIT OR Apache-2.0 |
| log | 0.4.28 | MIT OR Apache-2.0 |
| lru | 0.7.8 | MIT |
| lru-slab | 0.1.2 | MIT OR Apache-2.0 OR Zlib |
| lz4 | 1.28.1 | MIT |
| lz4-sys | 1.11.1+lz4-1.10.0 | MIT |
| macro_magic | 0.5.1 | MIT |
| macro_magic_core | 0.5.1 | MIT |
| macro_magic_core_macros | 0.5.1 | MIT |
| macro_magic_macros | 0.5.1 | MIT |
| matchers | 0.2.0 | MIT |
| matrixmultiply | 0.3.10 | MIT/Apache-2.0 |
| memchr | 2.7.5 | Unlicense OR MIT |
| memmap2 | 0.5.10 | MIT OR Apache-2.0 |
| memmap2 | 0.9.11 | MIT OR Apache-2.0 |
| memory-db | 0.34.0 | Apache-2.0 |
| merlin | 3.0.0 | MIT |
| minimal-lexical | 0.2.1 | MIT/Apache-2.0 |
| miniz_oxide | 0.8.9 | MIT OR Zlib OR Apache-2.0 |
| mio | 1.0.4 | MIT |
| mixnet | 0.7.0 | MIT |
| ml-kem | 0.2.1 | Apache-2.0 OR MIT |
| mockall | 0.13.1 | MIT OR Apache-2.0 |
| mockall_derive | 0.13.1 | MIT OR Apache-2.0 |
| moka | 0.12.10 | MIT OR Apache-2.0 |
| multiaddr | 0.18.2 | MIT |
| multibase | 0.9.1 | MIT |
| multihash | 0.19.5 | MIT |
| multihash-codetable | 0.2.2 | MIT |
| multihash-derive | 0.9.3 | MIT |
| multihash-derive-impl | 0.1.3 | MIT |
| multimap | 0.10.1 | MIT OR Apache-2.0 |
| nalgebra | 0.33.2 | Apache-2.0 |
| names | 0.14.0 | MIT |
| ndk-context | 0.1.1 | MIT OR Apache-2.0 |
| network-interface | 2.0.3 | MIT OR Apache-2.0 |
| no-std-compat | 0.4.1 | MIT |
| nohash-hasher | 0.2.0 | Apache-2.0 OR MIT |
| nom | 7.1.3 | MIT |
| nonempty | 0.7.0 | MIT |
| nonzero_ext | 0.3.0 | Apache-2.0 |
| nu-ansi-term | 0.50.3 | MIT |
| num | 0.4.3 | MIT OR Apache-2.0 |
| num-bigint | 0.4.6 | MIT OR Apache-2.0 |
| num-complex | 0.4.6 | MIT OR Apache-2.0 |
| num-conv | 0.2.2 | MIT OR Apache-2.0 |
| num-format | 0.4.4 | MIT/Apache-2.0 |
| num-integer | 0.1.46 | MIT OR Apache-2.0 |
| num-iter | 0.1.45 | MIT OR Apache-2.0 |
| num-rational | 0.4.2 | MIT OR Apache-2.0 |
| num-traits | 0.2.19 | MIT OR Apache-2.0 |
| num_cpus | 1.17.0 | MIT OR Apache-2.0 |
| object | 0.36.7 | Apache-2.0 OR MIT |
| object | 0.37.3 | Apache-2.0 OR MIT |
| oid-registry | 0.8.1 | MIT OR Apache-2.0 |
| once_cell | 1.21.3 | MIT OR Apache-2.0 |
| opaque-debug | 0.2.3 | MIT OR Apache-2.0 |
| opaque-debug | 0.3.1 | MIT OR Apache-2.0 |
| openssl-probe | 0.1.6 | MIT/Apache-2.0 |
| option-ext | 0.2.0 | MPL-2.0 |
| pallet-balances | 46.0.0 | Apache-2.0 |
| pallet-mining-rewards | 0.1.0 | Apache-2.0 |
| pallet-multisig | 1.0.0 | MIT-0 |
| pallet-preimage | 45.0.0 | Apache-2.0 |
| pallet-qpow | 0.1.0 | Apache-2.0 |
| pallet-ranked-collective | 45.0.0 | Apache-2.0 |
| pallet-referenda | 45.0.0 | Apache-2.0 |
| pallet-reversible-transfers | 40.0.1 | Apache-2.0 |
| pallet-scheduler | 40.1.0 | Apache-2.0 |
| pallet-timestamp | 44.0.0 | Apache-2.0 |
| pallet-transaction-payment | 45.0.0 | Apache-2.0 |
| pallet-transaction-payment-rpc | 48.0.0 | Apache-2.0 |
| pallet-transaction-payment-rpc-runtime-api | 45.0.0 | Apache-2.0 |
| pallet-treasury | 0.1.0 | Apache-2.0 |
| pallet-utility | 45.0.0 | Apache-2.0 |
| pallet-vesting | 0.1.0 | Apache-2.0 |
| pallet-wormhole | 0.1.0 | MIT-0 |
| pallet-zk-tree | 0.1.0 | MIT-0 |
| parity-db | 0.4.13 | MIT OR Apache-2.0 |
| parity-scale-codec | 3.7.5 | Apache-2.0 |
| parity-scale-codec-derive | 3.7.5 | Apache-2.0 |
| parity-wasm | 0.45.0 | MIT/Apache-2.0 |
| parking_lot | 0.11.2 | Apache-2.0/MIT |
| parking_lot | 0.12.4 | MIT OR Apache-2.0 |
| parking_lot_core | 0.8.6 | Apache-2.0/MIT |
| parking_lot_core | 0.9.11 | MIT OR Apache-2.0 |
| partial_sort | 0.2.0 | MIT/Apache-2.0 |
| password-hash | 0.5.0 | MIT OR Apache-2.0 |
| paste | 1.0.15 | MIT OR Apache-2.0 |
| pbkdf2 | 0.12.2 | MIT OR Apache-2.0 |
| pem | 3.0.5 | MIT |
| percent-encoding | 2.3.2 | MIT OR Apache-2.0 |
| petgraph | 0.6.5 | MIT OR Apache-2.0 |
| petgraph | 0.8.3 | MIT OR Apache-2.0 |
| picosimd | 0.9.3 | MIT/Apache-2.0 |
| pin-project | 1.1.10 | Apache-2.0 OR MIT |
| pin-project-internal | 1.1.10 | Apache-2.0 OR MIT |
| pin-project-lite | 0.2.16 | Apache-2.0 OR MIT |
| pin-utils | 0.1.0 | MIT OR Apache-2.0 |
| pkcs8 | 0.10.2 | Apache-2.0 OR MIT |
| pkg-config | 0.3.32 | MIT OR Apache-2.0 |
| plonky2_maybe_rayon | 1.0.0 | MIT OR Apache-2.0 |
| plonky2_util | 1.0.0 | MIT OR Apache-2.0 |
| polkavm | 0.26.0 | MIT/Apache-2.0 |
| polkavm-assembler | 0.26.0 | MIT/Apache-2.0 |
| polkavm-common | 0.26.0 | MIT/Apache-2.0 |
| polkavm-common | 0.30.0 | MIT/Apache-2.0 |
| polkavm-linker | 0.30.0 | MIT/Apache-2.0 |
| poly1305 | 0.8.0 | Apache-2.0 OR MIT |
| polyval | 0.6.2 | Apache-2.0 OR MIT |
| portable-atomic | 1.15.0 | Apache-2.0 OR MIT |
| postcard | 1.1.3 | MIT OR Apache-2.0 |
| potential_utf | 0.1.3 | Unicode-3.0 |
| powerfmt | 0.2.0 | MIT OR Apache-2.0 |
| ppv-lite86 | 0.2.21 | MIT OR Apache-2.0 |
| predicates | 3.1.3 | MIT OR Apache-2.0 |
| predicates-core | 1.0.9 | MIT OR Apache-2.0 |
| predicates-tree | 1.0.12 | MIT OR Apache-2.0 |
| prefix-trie | 0.8.4 | MIT OR Apache-2.0 |
| prettyplease | 0.2.37 | MIT OR Apache-2.0 |
| primitive-types | 0.10.1 | MIT OR Apache-2.0 |
| primitive-types | 0.13.1 | MIT OR Apache-2.0 |
| proc-macro-crate | 3.4.0 | MIT OR Apache-2.0 |
| proc-macro-error | 1.0.4 | MIT OR Apache-2.0 |
| proc-macro-error-attr | 1.0.4 | MIT OR Apache-2.0 |
| proc-macro-warning | 1.84.1 | GPL-3.0 OR Apache-2.0 |
| proc-macro2 | 1.0.107 | MIT OR Apache-2.0 |
| prometheus | 0.13.4 | Apache-2.0 |
| prost | 0.12.6 | Apache-2.0 |
| prost | 0.13.5 | Apache-2.0 |
| prost | 0.14.3 | Apache-2.0 |
| prost-build | 0.13.5 | Apache-2.0 |
| prost-build | 0.14.3 | Apache-2.0 |
| prost-derive | 0.12.6 | Apache-2.0 |
| prost-derive | 0.13.5 | Apache-2.0 |
| prost-derive | 0.14.3 | Apache-2.0 |
| prost-types | 0.13.5 | Apache-2.0 |
| prost-types | 0.14.3 | Apache-2.0 |
| pulley-interpreter | 36.0.20 | Apache-2.0 WITH LLVM-exception |
| pulley-macros | 36.0.20 | Apache-2.0 WITH LLVM-exception |
| qp-dilithium-crypto | 0.7.0 | MIT |
| qp-header | 0.1.0 | Apache-2.0 |
| qp-high-security | 0.1.0 | Apache-2.0 |
| qp-plonky2 | 1.5.5 | MIT OR Apache-2.0 |
| qp-plonky2-core | 1.5.5 | MIT OR Apache-2.0 |
| qp-plonky2-field | 1.5.5 | MIT OR Apache-2.0 |
| qp-plonky2-verifier | 1.5.5 | MIT OR Apache-2.0 |
| qp-poseidon-core | 3.1.0 | MIT-0 |
| qp-rusty-crystals-dilithium | 4.1.1 | GPL-3.0 |
| qp-rusty-crystals-hdwallet | 4.1.1 | GPL-3.0 |
| qp-scheduler | 0.1.0 | Apache-2.0 |
| qp-wormhole | 0.1.0 | Apache-2.0 |
| qp-wormhole-aggregator | 4.3.0 | MIT |
| qp-wormhole-circuit | 4.3.0 | MIT |
| qp-wormhole-circuit-builder | 4.3.0 | MIT |
| qp-wormhole-inputs | 4.3.0 | MIT |
| qp-wormhole-prover | 4.3.0 | MIT |
| qp-wormhole-verifier | 4.3.0 | MIT |
| qp-zk-circuits-common | 4.3.0 | MIT |
| qpow-math | 0.1.0 | (see note)<br>No per-crate licence field; an upstream workspace member, covered by the upstream repository root LICENSE (MIT). |
| quanta | 0.12.6 | MIT |
| quantus-miner-api | 0.3.0 | MIT-0 |
| quantus-node | 1.0.5 | Apache-2.0 |
| quantus-runtime | 1.0.2-Qm | Apache-2.0 |
| quinn | 0.11.11 | MIT OR Apache-2.0 |
| quinn-proto | 0.11.19 | MIT OR Apache-2.0 |
| quinn-udp | 0.5.16 | MIT OR Apache-2.0 |
| quote | 1.0.47 | MIT OR Apache-2.0 |
| rand | 0.10.1 | MIT OR Apache-2.0 |
| rand | 0.8.6 | MIT OR Apache-2.0 |
| rand | 0.9.5 | MIT OR Apache-2.0 |
| rand_chacha | 0.3.1 | MIT OR Apache-2.0 |
| rand_chacha | 0.9.0 | MIT OR Apache-2.0 |
| rand_core | 0.10.0 | MIT OR Apache-2.0 |
| rand_core | 0.6.4 | MIT OR Apache-2.0 |
| rand_core | 0.9.3 | MIT OR Apache-2.0 |
| rand_distr | 0.4.3 | MIT OR Apache-2.0 |
| rand_pcg | 0.10.2 | MIT OR Apache-2.0 |
| rand_pcg | 0.3.1 | MIT OR Apache-2.0 |
| rawpointer | 0.2.1 | MIT/Apache-2.0 |
| rayon | 1.11.0 | MIT OR Apache-2.0 |
| rayon-core | 1.13.0 | MIT OR Apache-2.0 |
| rcgen | 0.13.2 | MIT OR Apache-2.0 |
| ref-cast | 1.0.24 | MIT OR Apache-2.0 |
| ref-cast-impl | 1.0.24 | MIT OR Apache-2.0 |
| regalloc2 | 0.12.2 | Apache-2.0 WITH LLVM-exception |
| regalloc2 | 0.9.3 | Apache-2.0 WITH LLVM-exception |
| regex | 1.11.2 | MIT OR Apache-2.0 |
| regex-automata | 0.4.10 | MIT OR Apache-2.0 |
| regex-syntax | 0.8.6 | MIT OR Apache-2.0 |
| resolv-conf | 0.7.5 | MIT OR Apache-2.0 |
| rfc6979 | 0.4.0 | Apache-2.0 OR MIT |
| ring | 0.17.14 | Apache-2.0 AND ISC |
| rocksdb | 0.24.0 | Apache-2.0 |
| route-recognizer | 0.3.1 | MIT |
| rpassword | 7.5.4 | Apache-2.0 |
| rtoolbox | 0.0.3 | Apache-2.0 |
| rustc-demangle | 0.1.26 | MIT/Apache-2.0 |
| rustc-hash | 1.1.0 | Apache-2.0/MIT |
| rustc-hash | 2.1.1 | Apache-2.0 OR MIT |
| rustc-hex | 2.1.0 | MIT/Apache-2.0 |
| rustc_version | 0.4.1 | MIT OR Apache-2.0 |
| rusticata-macros | 4.1.0 | MIT/Apache-2.0 |
| rustix | 1.1.6 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| rustls | 0.23.46 | Apache-2.0 OR ISC OR MIT |
| rustls-native-certs | 0.8.1 | Apache-2.0 OR ISC OR MIT |
| rustls-pki-types | 1.12.0 | MIT OR Apache-2.0 |
| rustls-webpki | 0.103.16 | ISC |
| rustversion | 1.0.22 | MIT OR Apache-2.0 |
| safe_arch | 0.7.4 | Zlib OR Apache-2.0 OR MIT |
| same-file | 1.0.6 | Unlicense/MIT |
| sc-allocator | 35.0.0 | Apache-2.0 |
| sc-basic-authorship | 0.53.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-block-builder | 0.48.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-chain-spec | 48.0.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-chain-spec-derive | 12.0.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-cli | 0.57.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-client-api | 44.0.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-client-db | 0.51.3 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-consensus | 0.54.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-consensus-qpow | 0.1.0 | Apache-2.0 |
| sc-executor | 0.47.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-executor-common | 0.43.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-executor-polkavm | 0.40.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-executor-wasmtime | 0.43.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-informant | 0.54.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-keystore | 39.0.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-mixnet | 0.25.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-network | 0.55.1 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-network-common | 0.52.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-network-sync | 0.54.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-network-transactions | 0.54.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-network-types | 0.20.3 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-offchain | 50.0.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-proposer-metrics | 0.20.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-rpc | 50.0.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-rpc-api | 0.54.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-rpc-server | 27.0.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-rpc-spec-v2 | 0.55.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-service | 0.56.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-state-db | 0.41.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-sysinfo | 46.0.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-telemetry | 30.0.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-tracing | 44.0.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-tracing-proc-macro | 11.1.0 | Apache-2.0 |
| sc-transaction-pool | 44.1.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-transaction-pool-api | 43.0.0 | GPL-3.0-or-later WITH Classpath-exception-2.0 |
| sc-utils | 20.0.0 | Apache-2.0 |
| scale-info | 2.11.6 | Apache-2.0 |
| scale-info-derive | 2.11.6 | Apache-2.0 |
| schnellru | 0.2.4 | MIT/Apache-2.0 |
| schnorrkel | 0.11.5 | BSD-3-Clause |
| scopeguard | 1.2.0 | MIT OR Apache-2.0 |
| scratch | 1.0.9 | MIT OR Apache-2.0 |
| sec1 | 0.7.3 | Apache-2.0 OR MIT |
| secp256k1 | 0.28.2 | CC0-1.0 |
| secp256k1-sys | 0.9.2 | CC0-1.0 |
| secrecy | 0.8.0 | Apache-2.0 OR MIT |
| semver | 0.6.0 | MIT/Apache-2.0 |
| semver | 1.0.27 | MIT OR Apache-2.0 |
| semver-parser | 0.7.0 | MIT/Apache-2.0 |
| serde | 1.0.228 | MIT OR Apache-2.0 |
| serde_bytes | 0.11.19 | MIT OR Apache-2.0 |
| serde_core | 1.0.228 | MIT OR Apache-2.0 |
| serde_derive | 1.0.228 | MIT OR Apache-2.0 |
| serde_json | 1.0.150 | MIT OR Apache-2.0 |
| serde_spanned | 0.6.9 | MIT OR Apache-2.0 |
| serde_with | 3.22.0 | MIT OR Apache-2.0 |
| serde_with_macros | 3.22.0 | MIT OR Apache-2.0 |
| serdect | 0.2.0 | Apache-2.0 OR MIT |
| sha1 | 0.10.6 | MIT OR Apache-2.0 |
| sha2 | 0.10.9 | MIT OR Apache-2.0 |
| sha2 | 0.11.0 | MIT OR Apache-2.0 |
| sha2 | 0.9.9 | MIT OR Apache-2.0 |
| sha3 | 0.10.8 | MIT OR Apache-2.0 |
| sharded-slab | 0.1.7 | MIT |
| shlex | 1.3.0 | MIT OR Apache-2.0 |
| signal-hook-registry | 1.4.6 | Apache-2.0/MIT |
| signature | 2.2.0 | Apache-2.0 OR MIT |
| simba | 0.9.1 | Apache-2.0 |
| simd_cesu8 | 1.1.1 | Apache-2.0 OR MIT |
| simdutf8 | 0.1.5 | MIT OR Apache-2.0 |
| simple-dns | 0.11.2 | MIT |
| simple-mermaid | 0.1.1 | MIT |
| siphasher | 0.3.11 | MIT/Apache-2.0 |
| siphasher | 1.0.1 | MIT/Apache-2.0 |
| slab | 0.4.11 | MIT |
| slice-group-by | 0.3.1 | MIT |
| smallvec | 1.15.1 | MIT OR Apache-2.0 |
| snap | 1.1.1 | BSD-3-Clause |
| socket2 | 0.5.10 | MIT OR Apache-2.0 |
| socket2 | 0.6.0 | MIT OR Apache-2.0 |
| soketto | 0.8.1 | Apache-2.0 OR MIT |
| sp-api | 40.0.0 | Apache-2.0 |
| sp-api-proc-macro | 26.0.0 | Apache-2.0 |
| sp-application-crypto | 44.0.0 | Apache-2.0 |
| sp-arithmetic | 28.0.1 | Apache-2.0 |
| sp-block-builder | 40.0.0 | Apache-2.0 |
| sp-blockchain | 43.0.0 | Apache-2.0 |
| sp-consensus | 0.46.0 | Apache-2.0 |
| sp-consensus-grandpa | 27.0.0 | Apache-2.0 |
| sp-consensus-qpow | 0.1.0 | Apache-2.0 |
| sp-core | 39.0.0 | Apache-2.0 |
| sp-crypto-hashing | 0.1.0 | Apache-2.0 |
| sp-crypto-hashing-proc-macro | 0.1.0 | Apache-2.0 |
| sp-database | 10.0.2 | Apache-2.0 |
| sp-debug-derive | 14.0.0 | Apache-2.0 |
| sp-externalities | 0.31.0 | Apache-2.0 |
| sp-genesis-builder | 0.21.0 | Apache-2.0 |
| sp-inherents | 40.0.0 | Apache-2.0 |
| sp-io | 44.0.0 | Apache-2.0 |
| sp-keyring | 45.0.0 | Apache-2.0 |
| sp-keystore | 0.45.0 | Apache-2.0 |
| sp-maybe-compressed-blob | 11.1.0 | Apache-2.0 |
| sp-metadata-ir | 0.12.3 | Apache-2.0 |
| sp-mixnet | 0.18.0 | Apache-2.0 |
| sp-offchain | 40.0.0 | Apache-2.0 |
| sp-panic-handler | 13.0.2 | Apache-2.0 |
| sp-rpc | 37.0.0 | Apache-2.0 |
| sp-runtime | 45.0.0 | Apache-2.0 |
| sp-runtime-interface | 33.0.0 | Apache-2.0 |
| sp-runtime-interface-proc-macro | 20.0.0 | Apache-2.0 |
| sp-session | 42.0.0 | Apache-2.0 |
| sp-staking | 42.1.0 | Apache-2.0 |
| sp-state-machine | 0.49.0 | Apache-2.0 |
| sp-statement-store | 24.0.0 | Apache-2.0 |
| sp-std | 14.0.0 | Apache-2.0 |
| sp-storage | 22.0.0 | Apache-2.0 |
| sp-timestamp | 40.0.0 | Apache-2.0 |
| sp-tracing | 19.0.0 | Apache-2.0 |
| sp-transaction-pool | 40.0.0 | Apache-2.0 |
| sp-transaction-storage-proof | 40.0.0 | Apache-2.0 |
| sp-trie | 42.0.1 | Apache-2.0 |
| sp-version | 43.0.0 | Apache-2.0 |
| sp-version-proc-macro | 15.0.0 | Apache-2.0 |
| sp-wasm-interface | 24.0.0 | Apache-2.0 |
| sp-weights | 33.2.0 | Apache-2.0 |
| spin | 0.9.9 | MIT |
| spinning_top | 0.3.0 | MIT/Apache-2.0 |
| spki | 0.7.3 | Apache-2.0 OR MIT |
| ss58-registry | 1.51.0 | Apache-2.0 |
| stable_deref_trait | 1.2.0 | MIT/Apache-2.0 |
| static_assertions | 1.1.0 | MIT OR Apache-2.0 |
| static_init | 1.0.4 | MIT OR Apache-2.0 |
| static_init_macro | 1.0.4 | MIT OR Apache-2.0 |
| strsim | 0.11.1 | MIT |
| strum | 0.24.1 | MIT |
| strum | 0.26.3 | MIT |
| strum_macros | 0.24.3 | MIT |
| strum_macros | 0.26.4 | MIT |
| substrate-bip39 | 0.6.1 | Apache-2.0 |
| substrate-build-script-utils | 11.0.0 | Apache-2.0 |
| substrate-frame-rpc-system | 49.0.0 | Apache-2.0 |
| substrate-prometheus-endpoint | 0.17.7 | Apache-2.0 |
| substrate-wasm-builder | 31.1.0 | Apache-2.0 |
| subtle | 1.0.0 | BSD-3-Clause |
| subtle | 2.6.1 | BSD-3-Clause |
| syn | 1.0.109 | MIT OR Apache-2.0 |
| syn | 2.0.119 | MIT OR Apache-2.0 |
| syn | 3.0.3 | MIT OR Apache-2.0 |
| synstructure | 0.13.2 | MIT |
| sysinfo | 0.30.13 | MIT |
| tagptr | 0.2.0 | MIT/Apache-2.0 |
| target-lexicon | 0.13.5 | Apache-2.0 WITH LLVM-exception |
| tempfile | 3.22.0 | MIT OR Apache-2.0 |
| termcolor | 1.4.1 | Unlicense OR MIT |
| terminal_size | 0.4.3 | MIT OR Apache-2.0 |
| termtree | 0.5.1 | MIT |
| thiserror | 1.0.69 | MIT OR Apache-2.0 |
| thiserror | 2.0.18 | MIT OR Apache-2.0 |
| thiserror-impl | 1.0.69 | MIT OR Apache-2.0 |
| thiserror-impl | 2.0.18 | MIT OR Apache-2.0 |
| thiserror-impl-no-std | 2.0.2 | MIT OR Apache-2.0 |
| thiserror-no-std | 2.0.2 | MIT OR Apache-2.0 |
| thread_local | 1.1.9 | MIT OR Apache-2.0 |
| threadpool | 1.8.1 | MIT/Apache-2.0 |
| time | 0.3.47 | MIT OR Apache-2.0 |
| time-core | 0.1.8 | MIT OR Apache-2.0 |
| time-macros | 0.2.27 | MIT OR Apache-2.0 |
| tiny-keccak | 2.0.2 | CC0-1.0 |
| tinystr | 0.8.1 | Unicode-3.0 |
| tinyvec | 1.10.0 | Zlib OR Apache-2.0 OR MIT |
| tinyvec_macros | 0.1.1 | MIT OR Apache-2.0 OR Zlib |
| tokio | 1.47.1 | MIT |
| tokio-macros | 2.5.0 | MIT |
| tokio-rustls | 0.26.3 | MIT OR Apache-2.0 |
| tokio-stream | 0.1.17 | MIT |
| tokio-tungstenite | 0.27.0 | MIT |
| tokio-util | 0.7.16 | MIT |
| toml | 0.8.23 | MIT OR Apache-2.0 |
| toml_datetime | 0.6.11 | MIT OR Apache-2.0 |
| toml_datetime | 0.7.2 | MIT OR Apache-2.0 |
| toml_edit | 0.22.27 | MIT OR Apache-2.0 |
| toml_edit | 0.23.6 | MIT OR Apache-2.0 |
| toml_parser | 1.0.3 | MIT OR Apache-2.0 |
| toml_write | 0.1.2 | MIT OR Apache-2.0 |
| tower | 0.4.13 | MIT |
| tower-http | 0.5.2 | MIT |
| tower-layer | 0.3.3 | MIT |
| tower-service | 0.3.3 | MIT |
| tracing | 0.1.44 | MIT |
| tracing-attributes | 0.1.31 | MIT |
| tracing-core | 0.1.36 | MIT |
| tracing-futures | 0.2.5 | MIT |
| tracing-log | 0.2.0 | MIT |
| tracing-subscriber | 0.3.23 | MIT |
| trie-db | 0.30.0 | Apache-2.0 |
| trie-root | 0.18.0 | Apache-2.0 |
| try-lock | 0.2.5 | MIT |
| tt-call | 1.0.9 | MIT OR Apache-2.0 |
| tungstenite | 0.27.0 | MIT OR Apache-2.0 |
| tuplex | 0.1.2 | MIT/Apache-2.0 |
| twox-hash | 1.6.3 | MIT |
| typenum | 1.20.1 | MIT OR Apache-2.0 |
| uint | 0.10.0 | MIT OR Apache-2.0 |
| uint | 0.9.5 | MIT OR Apache-2.0 |
| unicode-ident | 1.0.19 | (MIT OR Apache-2.0) AND Unicode-3.0 |
| unicode-normalization | 0.1.25 | MIT OR Apache-2.0 |
| unicode-segmentation | 1.12.0 | MIT OR Apache-2.0 |
| unicode-width | 0.2.1 | MIT OR Apache-2.0 |
| unicode-xid | 0.2.6 | MIT OR Apache-2.0 |
| universal-hash | 0.5.1 | MIT OR Apache-2.0 |
| unroll | 0.1.5 | MIT OR Apache-2.0 |
| unsigned-varint | 0.8.0 | MIT |
| untrusted | 0.9.0 | ISC |
| url | 2.5.7 | MIT OR Apache-2.0 |
| utf-8 | 0.7.6 | MIT OR Apache-2.0 |
| utf8_iter | 1.0.4 | Apache-2.0 OR MIT |
| utf8parse | 0.2.2 | Apache-2.0 OR MIT |
| uuid | 1.18.1 | Apache-2.0 OR MIT |
| version_check | 0.9.5 | MIT/Apache-2.0 |
| void | 1.0.2 | MIT |
| walkdir | 2.5.0 | Unlicense/MIT |
| want | 0.3.1 | MIT |
| wasm-encoder | 0.236.1 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| wasm-instrument | 0.4.0 | MIT OR Apache-2.0 |
| wasm-opt | 0.116.1 | MIT / Apache-2.0 |
| wasm-opt-cxx-sys | 0.116.0 | MIT / Apache-2.0 |
| wasm-opt-sys | 0.116.0 | MIT / Apache-2.0 |
| wasm-timer | 0.2.5 | MIT |
| wasmparser | 0.236.1 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| wasmprinter | 0.236.1 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| wasmtime | 36.0.20 | Apache-2.0 WITH LLVM-exception |
| wasmtime-environ | 36.0.20 | Apache-2.0 WITH LLVM-exception |
| wasmtime-internal-asm-macros | 36.0.20 | Apache-2.0 WITH LLVM-exception |
| wasmtime-internal-cache | 36.0.20 | Apache-2.0 WITH LLVM-exception |
| wasmtime-internal-cranelift | 36.0.20 | Apache-2.0 WITH LLVM-exception |
| wasmtime-internal-jit-debug | 36.0.20 | Apache-2.0 WITH LLVM-exception |
| wasmtime-internal-jit-icache-coherence | 36.0.20 | Apache-2.0 WITH LLVM-exception |
| wasmtime-internal-math | 36.0.20 | Apache-2.0 WITH LLVM-exception |
| wasmtime-internal-slab | 36.0.20 | Apache-2.0 WITH LLVM-exception |
| wasmtime-internal-unwinder | 36.0.20 | Apache-2.0 WITH LLVM-exception |
| wasmtime-internal-versioned-export-macros | 36.0.20 | Apache-2.0 WITH LLVM-exception |
| web-time | 1.1.0 | MIT OR Apache-2.0 |
| wide | 0.7.33 | Zlib OR Apache-2.0 OR MIT |
| winnow | 0.7.13 | MIT |
| writeable | 0.6.1 | Unicode-3.0 |
| x25519-dalek | 2.0.1 | BSD-3-Clause |
| x509-parser | 0.18.1 | MIT OR Apache-2.0 |
| yamux | 0.13.10 | Apache-2.0 OR MIT |
| yasna | 0.5.2 | MIT OR Apache-2.0 |
| yoke | 0.8.0 | Unicode-3.0 |
| yoke-derive | 0.8.0 | Unicode-3.0 |
| zerocopy | 0.8.27 | BSD-2-Clause OR Apache-2.0 OR MIT |
| zerofrom | 0.1.6 | Unicode-3.0 |
| zerofrom-derive | 0.1.6 | Unicode-3.0 |
| zeroize | 1.8.2 | Apache-2.0 OR MIT |
| zeroize_derive | 1.4.2 | Apache-2.0 OR MIT |
| zerotrie | 0.2.2 | Unicode-3.0 |
| zerovec | 0.11.4 | Unicode-3.0 |
| zerovec-derive | 0.11.1 | Unicode-3.0 |
| zmij | 1.0.21 | MIT |
| zstd | 0.12.4 | MIT |
| zstd | 0.13.4 | BSD-3-Clause |
| zstd-safe | 6.0.6 | MIT/Apache-2.0 |
| zstd-safe | 7.4.0 | BSD-3-Clause |
| zstd-sys | 2.1.2+zstd.1.5.7 | BSD-3-Clause |

### Miner dependency list (229 crates)

| Crate | Version | Licence |
|---|---|---|
| ahash | 0.8.12 | MIT OR Apache-2.0 |
| aho-corasick | 1.1.4 | Unlicense OR MIT |
| android_system_properties | 0.1.5 | MIT/Apache-2.0 |
| anstream | 1.0.0 | MIT OR Apache-2.0 |
| anstyle | 1.0.14 | MIT OR Apache-2.0 |
| anstyle-parse | 1.0.0 | MIT OR Apache-2.0 |
| anstyle-query | 1.1.5 | MIT OR Apache-2.0 |
| anyhow | 1.0.104 | MIT OR Apache-2.0 |
| arrayvec | 0.7.6 | MIT OR Apache-2.0 |
| ash | 0.38.0+1.3.281 | MIT OR Apache-2.0 |
| autocfg | 1.5.0 | Apache-2.0 OR MIT |
| bit-set | 0.8.0 | Apache-2.0 OR MIT |
| bit-vec | 0.8.0 | Apache-2.0 OR MIT |
| bitflags | 2.11.0 | MIT OR Apache-2.0 |
| block-buffer | 0.10.4 | MIT OR Apache-2.0 |
| bytemuck | 1.25.0 | Zlib OR Apache-2.0 OR MIT |
| bytemuck_derive | 1.10.2 | Zlib OR Apache-2.0 OR MIT |
| byteorder | 1.5.0 | Unlicense OR MIT |
| bytes | 1.11.1 | MIT |
| cc | 1.2.57 | MIT OR Apache-2.0 |
| cfg-if | 1.0.4 | MIT OR Apache-2.0 |
| cfg_aliases | 0.2.1 | MIT |
| chacha20 | 0.10.0 | MIT OR Apache-2.0 |
| clap | 4.6.0 | MIT OR Apache-2.0 |
| clap_builder | 4.6.0 | MIT OR Apache-2.0 |
| clap_derive | 4.6.0 | MIT OR Apache-2.0 |
| clap_lex | 1.1.0 | MIT OR Apache-2.0 |
| codespan-reporting | 0.12.0 | Apache-2.0 |
| colorchoice | 1.0.5 | MIT OR Apache-2.0 |
| const-random | 0.1.18 | MIT OR Apache-2.0 |
| const-random-macro | 0.1.16 | MIT OR Apache-2.0 |
| cpufeatures | 0.2.17 | MIT OR Apache-2.0 |
| crossbeam-channel | 0.5.15 | MIT OR Apache-2.0 |
| crossbeam-deque | 0.8.6 | MIT OR Apache-2.0 |
| crossbeam-epoch | 0.9.20 | MIT OR Apache-2.0 |
| crossbeam-utils | 0.8.21 | MIT OR Apache-2.0 |
| crunchy | 0.2.4 | MIT |
| crypto-common | 0.1.7 | MIT OR Apache-2.0 |
| cudarc | 0.19.9 | MIT OR Apache-2.0 |
| digest | 0.10.7 | MIT OR Apache-2.0 |
| document-features | 0.2.12 | MIT OR Apache-2.0 |
| either | 1.15.0 | MIT OR Apache-2.0 |
| engine-cpu | 4.2.1 | (see note)<br>The miner project's own crate, covered by that project's LICENSE (Apache-2.0). |
| engine-cuda | 4.2.1 | (see note)<br>The miner project's own crate, covered by that project's LICENSE (Apache-2.0). |
| engine-gpu | 4.2.1 | (see note)<br>The miner project's own crate, covered by that project's LICENSE (Apache-2.0). |
| env_logger | 0.10.2 | MIT OR Apache-2.0 |
| equivalent | 1.0.2 | Apache-2.0 OR MIT |
| errno | 0.3.14 | MIT OR Apache-2.0 |
| find-msvc-tools | 0.1.9 | MIT OR Apache-2.0 |
| fixed-hash | 0.7.0 | MIT OR Apache-2.0 |
| fixed-hash | 0.8.0 | MIT OR Apache-2.0 |
| fnv | 1.0.7 | Apache-2.0 / MIT |
| foldhash | 0.1.5 | Zlib |
| foldhash | 0.2.0 | Zlib |
| futures | 0.3.32 | MIT OR Apache-2.0 |
| futures-channel | 0.3.32 | MIT OR Apache-2.0 |
| futures-core | 0.3.32 | MIT OR Apache-2.0 |
| futures-executor | 0.3.32 | MIT OR Apache-2.0 |
| futures-io | 0.3.32 | MIT OR Apache-2.0 |
| futures-macro | 0.3.32 | MIT OR Apache-2.0 |
| futures-sink | 0.3.32 | MIT OR Apache-2.0 |
| futures-task | 0.3.32 | MIT OR Apache-2.0 |
| futures-util | 0.3.32 | MIT OR Apache-2.0 |
| generic-array | 0.14.7 | MIT |
| getrandom | 0.2.17 | MIT OR Apache-2.0 |
| getrandom | 0.3.4 | MIT OR Apache-2.0 |
| getrandom | 0.4.2 | MIT OR Apache-2.0 |
| glow | 0.16.0 | MIT OR Apache-2.0 OR Zlib |
| gpu-alloc | 0.6.0 | MIT OR Apache-2.0 |
| gpu-alloc-types | 0.3.0 | MIT OR Apache-2.0 |
| gpu-descriptor | 0.3.2 | MIT OR Apache-2.0 |
| gpu-descriptor-types | 0.2.0 | MIT OR Apache-2.0 |
| half | 2.7.1 | MIT OR Apache-2.0 |
| hashbrown | 0.14.5 | MIT OR Apache-2.0 |
| hashbrown | 0.15.5 | MIT OR Apache-2.0 |
| hashbrown | 0.16.1 | MIT OR Apache-2.0 |
| heck | 0.5.0 | MIT OR Apache-2.0 |
| hex | 0.4.3 | MIT OR Apache-2.0 |
| hexf-parse | 0.2.1 | CC0-1.0 |
| humantime | 2.3.0 | MIT OR Apache-2.0 |
| indexmap | 2.13.0 | Apache-2.0 OR MIT |
| is-terminal | 0.4.17 | MIT |
| is_terminal_polyfill | 1.70.2 | MIT OR Apache-2.0 |
| itertools | 0.11.0 | MIT OR Apache-2.0 |
| itertools | 0.14.0 | MIT OR Apache-2.0 |
| itoa | 1.0.18 | MIT OR Apache-2.0 |
| jni-sys | 0.3.1 | MIT OR Apache-2.0 |
| jni-sys | 0.4.1 | MIT OR Apache-2.0 |
| jni-sys-macros | 0.4.1 | MIT OR Apache-2.0 |
| keccak-hash | 0.8.0 | MIT OR Apache-2.0 |
| khronos-egl | 6.0.0 | MIT/Apache-2.0 |
| lazy_static | 1.5.0 | MIT OR Apache-2.0 |
| libc | 0.2.183 | MIT OR Apache-2.0 |
| libloading | 0.8.9 | ISC |
| libloading | 0.9.0 | ISC |
| libm | 0.2.16 | MIT |
| litrs | 1.0.0 | MIT OR Apache-2.0 |
| lock_api | 0.4.14 | MIT OR Apache-2.0 |
| log | 0.4.29 | MIT OR Apache-2.0 |
| lru-slab | 0.1.3 | MIT OR Apache-2.0 OR Zlib |
| memchr | 2.8.0 | Unlicense OR MIT |
| metrics | 4.2.1 | (see note)<br>The miner project's own crate, covered by that project's LICENSE (Apache-2.0). |
| miner-cli | 4.2.1 | (see note)<br>The miner project's own crate, covered by that project's LICENSE (Apache-2.0). |
| miner-service | 4.2.1 | (see note)<br>The miner project's own crate, covered by that project's LICENSE (Apache-2.0). |
| mio | 1.1.1 | MIT |
| naga | 27.0.3 | MIT OR Apache-2.0 |
| ndk-sys | 0.6.0+11769913 | MIT OR Apache-2.0 |
| num | 0.4.3 | MIT OR Apache-2.0 |
| num-bigint | 0.4.6 | MIT OR Apache-2.0 |
| num-complex | 0.4.6 | MIT OR Apache-2.0 |
| num-integer | 0.1.46 | MIT OR Apache-2.0 |
| num-iter | 0.1.45 | MIT OR Apache-2.0 |
| num-rational | 0.4.2 | MIT OR Apache-2.0 |
| num-traits | 0.2.19 | MIT OR Apache-2.0 |
| num_cpus | 1.17.0 | MIT OR Apache-2.0 |
| once_cell | 1.21.4 | MIT OR Apache-2.0 |
| ordered-float | 5.1.0 | MIT |
| p3-dft | 0.3.0 | MIT OR Apache-2.0 |
| p3-field | 0.3.0 | MIT OR Apache-2.0 |
| p3-goldilocks | 0.3.0 | MIT OR Apache-2.0 |
| p3-matrix | 0.3.0 | MIT OR Apache-2.0 |
| p3-maybe-rayon | 0.3.0 | MIT OR Apache-2.0 |
| p3-mds | 0.3.0 | MIT OR Apache-2.0 |
| p3-poseidon2 | 0.3.0 | MIT OR Apache-2.0 |
| p3-symmetric | 0.3.0 | MIT OR Apache-2.0 |
| p3-util | 0.3.0 | MIT OR Apache-2.0 |
| parking_lot | 0.12.5 | MIT OR Apache-2.0 |
| parking_lot_core | 0.9.12 | MIT OR Apache-2.0 |
| paste | 1.0.15 | MIT OR Apache-2.0 |
| pin-project-lite | 0.2.17 | Apache-2.0 OR MIT |
| plonky2_maybe_rayon | 1.0.0 | MIT OR Apache-2.0 |
| plonky2_util | 1.0.0 | MIT OR Apache-2.0 |
| pow-core | 4.2.1 | MIT OR Apache-2.0 |
| ppv-lite86 | 0.2.21 | MIT OR Apache-2.0 |
| primitive-types | 0.10.1 | MIT OR Apache-2.0 |
| primitive-types | 0.13.1 | MIT OR Apache-2.0 |
| proc-macro2 | 1.0.106 | MIT OR Apache-2.0 |
| profiling | 1.0.17 | MIT OR Apache-2.0 |
| prometheus | 0.13.4 | Apache-2.0 |
| qp-plonky2 | 1.1.5 | MIT OR Apache-2.0 |
| qp-plonky2-core | 1.1.5 | MIT OR Apache-2.0 |
| qp-plonky2-field | 1.1.5 | MIT OR Apache-2.0 |
| qp-plonky2-verifier | 1.1.5 | MIT OR Apache-2.0 |
| qp-poseidon-constants | 1.1.0 | MIT-0 |
| qp-poseidon-core | 2.1.0 | MIT-0 |
| qp-poseidon-core | 3.1.0 | MIT-0 |
| qpow-math | 0.1.0 | (see note)<br>No per-crate licence field; an upstream workspace member of the chain repository, covered by its root LICENSE (MIT). |
| quantus-miner-api | 0.3.0 | MIT-0 |
| quic-transport | 4.2.1 | (see note)<br>The miner project's own crate, covered by that project's LICENSE (Apache-2.0). |
| quinn | 0.11.12 | MIT OR Apache-2.0 |
| quinn-proto | 0.11.19 | MIT OR Apache-2.0 |
| quinn-udp | 0.5.16 | MIT OR Apache-2.0 |
| quote | 1.0.45 | MIT OR Apache-2.0 |
| rand | 0.10.3 | MIT OR Apache-2.0 |
| rand | 0.8.5 | MIT OR Apache-2.0 |
| rand | 0.9.2 | MIT OR Apache-2.0 |
| rand_chacha | 0.3.1 | MIT OR Apache-2.0 |
| rand_chacha | 0.9.0 | MIT OR Apache-2.0 |
| rand_core | 0.10.0 | MIT OR Apache-2.0 |
| rand_core | 0.6.4 | MIT OR Apache-2.0 |
| rand_core | 0.9.5 | MIT OR Apache-2.0 |
| rand_pcg | 0.10.2 | MIT OR Apache-2.0 |
| raw-window-handle | 0.6.2 | MIT OR Apache-2.0 OR Zlib |
| rayon | 1.11.0 | MIT OR Apache-2.0 |
| rayon-core | 1.13.0 | MIT OR Apache-2.0 |
| regex | 1.12.3 | MIT OR Apache-2.0 |
| regex-automata | 0.4.14 | MIT OR Apache-2.0 |
| regex-syntax | 0.8.10 | MIT OR Apache-2.0 |
| renderdoc-sys | 1.1.0 | MIT OR Apache-2.0 |
| ring | 0.17.14 | Apache-2.0 AND ISC |
| rustc-hash | 1.1.0 | Apache-2.0/MIT |
| rustc-hash | 2.1.3 | Apache-2.0 OR MIT |
| rustc-hex | 2.1.0 | MIT/Apache-2.0 |
| rustc_version | 0.4.1 | MIT OR Apache-2.0 |
| rustls | 0.23.46 | Apache-2.0 OR ISC OR MIT |
| rustls-pki-types | 1.14.0 | MIT OR Apache-2.0 |
| rustls-webpki | 0.103.16 | ISC |
| scopeguard | 1.2.0 | MIT OR Apache-2.0 |
| semver | 1.0.27 | MIT OR Apache-2.0 |
| serde | 1.0.228 | MIT OR Apache-2.0 |
| serde_core | 1.0.228 | MIT OR Apache-2.0 |
| serde_derive | 1.0.228 | MIT OR Apache-2.0 |
| serde_json | 1.0.149 | MIT OR Apache-2.0 |
| sha2 | 0.10.9 | MIT OR Apache-2.0 |
| shlex | 1.3.0 | MIT OR Apache-2.0 |
| signal-hook-registry | 1.4.8 | MIT OR Apache-2.0 |
| slab | 0.4.12 | MIT |
| smallvec | 1.15.1 | MIT OR Apache-2.0 |
| socket2 | 0.6.3 | MIT OR Apache-2.0 |
| spirv | 0.3.0+sdk-1.3.268.0 | Apache-2.0 |
| static_assertions | 1.1.0 | MIT OR Apache-2.0 |
| strength_reduce | 0.2.4 | MIT OR Apache-2.0 |
| strsim | 0.11.1 | MIT |
| subtle | 2.6.1 | BSD-3-Clause |
| syn | 1.0.109 | MIT OR Apache-2.0 |
| syn | 2.0.117 | MIT OR Apache-2.0 |
| termcolor | 1.4.1 | Unlicense OR MIT |
| thiserror | 1.0.69 | MIT OR Apache-2.0 |
| thiserror | 2.0.18 | MIT OR Apache-2.0 |
| thiserror-impl | 1.0.69 | MIT OR Apache-2.0 |
| thiserror-impl | 2.0.18 | MIT OR Apache-2.0 |
| tiny-keccak | 2.0.2 | CC0-1.0 |
| tinyvec | 1.11.0 | Zlib OR Apache-2.0 OR MIT |
| tinyvec_macros | 0.1.1 | MIT OR Apache-2.0 OR Zlib |
| tokio | 1.50.0 | MIT |
| tokio-macros | 2.6.1 | MIT |
| tracing | 0.1.44 | MIT |
| tracing-attributes | 0.1.31 | MIT |
| tracing-core | 0.1.36 | MIT |
| transpose | 0.2.3 | MIT OR Apache-2.0 |
| typenum | 1.19.0 | MIT OR Apache-2.0 |
| uint | 0.10.0 | MIT OR Apache-2.0 |
| uint | 0.9.5 | MIT OR Apache-2.0 |
| unicode-ident | 1.0.24 | (MIT OR Apache-2.0) AND Unicode-3.0 |
| unicode-width | 0.2.2 | MIT OR Apache-2.0 |
| unroll | 0.1.5 | MIT OR Apache-2.0 |
| untrusted | 0.9.0 | ISC |
| utf8parse | 0.2.2 | Apache-2.0 OR MIT |
| version_check | 0.9.5 | MIT/Apache-2.0 |
| web-time | 1.1.0 | MIT OR Apache-2.0 |
| wgpu | 27.0.1 | MIT OR Apache-2.0 |
| wgpu-core | 27.0.3 | MIT OR Apache-2.0 |
| wgpu-core-deps-windows-linux-android | 27.0.0 | MIT OR Apache-2.0 |
| wgpu-hal | 27.0.4 | MIT OR Apache-2.0 |
| wgpu-types | 27.0.1 | MIT OR Apache-2.0 |
| zerocopy | 0.8.47 | BSD-2-Clause OR Apache-2.0 OR MIT |
| zerocopy-derive | 0.8.47 | BSD-2-Clause OR Apache-2.0 OR MIT |
| zeroize | 1.8.2 | Apache-2.0 OR MIT |
| zmij | 1.0.21 | MIT |

The licence texts are in the app under Settings → About → Open-source licences, and in `app/assets/licenses/` in this repository.
