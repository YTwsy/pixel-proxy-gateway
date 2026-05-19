The pinned android/arm64 GOST executable is packaged as a native library at:

  app/src/main/jniLibs/arm64-v8a/libgost.so

Build it with:

  tools/build-gost-android-arm64.sh

The build script pins both tag v3.2.6 and commit
340ba32ef0bebc7293908007cc423dd5f33dd88c.

This assets directory stores provenance metadata:

  app/src/main/assets/gost/android-arm64/gost.sha256
  app/src/main/assets/gost/android-arm64/gost.tag
  app/src/main/assets/gost/android-arm64/gost.commit

Verify local source, binary, and metadata with:

  scripts/verify_gost_provenance.sh
