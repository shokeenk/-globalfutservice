#!/usr/bin/env bash
#
# Offline verification of the money-handling core.
#
# Compiles com.globalfutservice.domain.** with a bare JDK and runs an assertion suite
# over the pricing arithmetic, quote signing, credential encryption, the order state
# machine and the Razorpay signature checks.
#
# No Maven, no network, no database. Useful on a bad connection, and as the first gate in
# CI before any dependency is downloaded.
#
set -euo pipefail
cd "$(dirname "$0")"

OUT=target/verify-classes
rm -rf "$OUT"
mkdir -p "$OUT"

echo "Compiling domain core..."
find src/main/java/com/globalfutservice/domain -name '*.java' > "$OUT/sources.txt"
javac -Xlint:all -d "$OUT" @"$OUT/sources.txt"
javac -d "$OUT" -cp "$OUT" tools/DomainVerification.java

echo "Running verification..."
java -cp "$OUT" DomainVerification
