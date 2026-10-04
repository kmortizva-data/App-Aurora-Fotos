#!/usr/bin/env bash
# Offline sanity build: compiles resources with aapt2, Kotlin with kotlinc against a bare
# android.jar and runs the JVM unit tests. It does NOT replace the Gradle/AGP build that
# GitHub Actions runs; it exists so the code can be type-checked in sandboxes that cannot
# reach dl.google.com.
#
#   ANDROID_JAR=/opt/android-sdk/platforms/android-35.jar KOTLINC=/opt/kotlinc/bin/kotlinc \
#   LIBS=/opt/localbuild/libs tools/local-check.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_JAR="${ANDROID_JAR:-/opt/android-sdk/platforms/android-35.jar}"
# aapt2 2.19 cannot read the ZIP64 android-all jar, so framework resources come from a slim copy.
RES_JAR="${RES_JAR:-/opt/android-sdk/platforms/android-35-res.jar}"
KOTLINC="${KOTLINC:-/opt/kotlinc/bin/kotlinc}"
AAPT2="${AAPT2:-/opt/android-sdk/build-tools/36.0.0/aapt2}"
LIBS="${LIBS:-/opt/localbuild/libs}"
OUT="$ROOT/tools/out"
rm -rf "$OUT"; mkdir -p "$OUT/res" "$OUT/classes" "$OUT/gen" "$OUT/test-classes"

CP="$ANDROID_JAR:$LIBS/kotlinx-coroutines-core-jvm-1.10.2.jar:$LIBS/kotlinx-coroutines-android-1.10.2.jar"

echo "== aapt2 compile/link"
"$AAPT2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res/res.zip"
# AGP injects the namespace; aapt2 on its own needs the package attribute.
sed 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.aurorafotos">#' \
  "$ROOT/app/src/main/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$AAPT2" link -o "$OUT/app.ap_" -I "$RES_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version 34 --target-sdk-version 35 "$OUT/res/res.zip"

echo "== kotlinc (main)"
# android-all is built from AOSP sources and carries @android.annotation.Nullable on APIs that the
# SDK stub jar leaves unannotated (platform types), so ignore those annotations here.
NULLABILITY_FLAGS="-Xnullability-annotations=@android.annotation:ignore -Xnullability-annotations=@androidx.annotation:ignore"
"$KOTLINC" -no-reflect -jvm-target 17 -Xno-call-assertions -Xno-param-assertions $NULLABILITY_FLAGS \
  -cp "$CP" -d "$OUT/classes" \
  $(find "$ROOT/app/src/main/java" -name '*.kt') $(find "$OUT/gen" -name '*.java') 2>&1 \
  | { grep -v -E 'JAVA_TOOL_OPTIONS|Picked up' || true; }
test "${PIPESTATUS[0]}" -eq 0 || { echo "kotlinc (main) FAILED"; exit 1; }
javac -nowarn -proc:none -cp "$CP:$OUT/classes" -d "$OUT/classes" $(find "$OUT/gen" -name '*.java')

echo "== kotlinc (tests) + junit"
TESTS=$(find "$ROOT/app/src/test/java" -name '*.kt' 2>/dev/null || true)
if [ -n "$TESTS" ]; then
  "$KOTLINC" -no-reflect -jvm-target 17 -cp "$CP:$OUT/classes:$LIBS/junit-4.13.2.jar" -d "$OUT/test-classes" $TESTS 2>&1 \
    | { grep -v -E 'JAVA_TOOL_OPTIONS|Picked up' || true; }
  test "${PIPESTATUS[0]}" -eq 0 || { echo "kotlinc (tests) FAILED"; exit 1; }
  CLASSES=$(cd "$OUT/test-classes" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g')
  java -cp "$OUT/test-classes:$OUT/classes:$CP:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar:/opt/kotlinc/lib/kotlin-stdlib.jar" \
    org.junit.runner.JUnitCore $CLASSES 2>&1 | { grep -v -E 'JAVA_TOOL_OPTIONS|Picked up' || true; }
  test "${PIPESTATUS[0]}" -eq 0 || { echo "unit tests FAILED"; exit 1; }
fi
echo "== OK"
