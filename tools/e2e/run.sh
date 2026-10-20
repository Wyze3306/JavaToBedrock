#!/usr/bin/env bash
# End-to-end test against a real PowerNukkitX server, without any Minecraft client.
#
#   tools/e2e/run.sh <powernukkitx.jar> [scenario=default] [seconds=30]
#
# Builds the proxy, prepares a throw-away PowerNukkitX server in build/e2e/pnx (xbox-auth off, the
# headless player is op), starts it, starts the proxy in front of it, runs the headless Java client
# with the scenario (src/test/java/.../e2e/scenario, "foo_bar" = FooBarScenario) and prints its report.
# Exit code: the client's (0 when every check passed). Logs: build/e2e/{pnx,proxy,client}.log
# GRADLE=/path/to/gradle overrides the wrapper; PNX_PORT, JAVA_PORT, HTTP_PORT override the ports.
set -uo pipefail
PNX_JAR=$(realpath "${1:?usage: run.sh <powernukkitx.jar> [scenario] [seconds]}")
SCENARIO=${2:-default}; SECONDS_TO_PLAY=${3:-30}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$ROOT/build/e2e; PNX=$OUT/pnx; PROXY=$OUT/proxy
PNX_PORT=${PNX_PORT:-19232}; JAVA_PORT=${JAVA_PORT:-25665}; HTTP_PORT=${HTTP_PORT:-8865}
mkdir -p "$PNX" "$PROXY"
pids=()
cleanup() { for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null; done; }
trap cleanup EXIT

GRADLE=${GRADLE:-$ROOT/gradlew}
INIT=$OUT/classpath.gradle
cat > "$INIT" <<'GRADLE'
allprojects { tasks.register("printTestClasspath") { doLast { println(project.sourceSets.test.runtimeClasspath.asPath) } } }
GRADLE
echo "== building"
(cd "$ROOT" && "$GRADLE" -q shadowJar testClasses) || exit 2
CP=$(cd "$ROOT" && "$GRADLE" -q -I "$INIT" printTestClasspath | tail -1)

cp "$PNX_JAR" "$PNX/powernukkitx.jar"
start_pnx() {
  (cd "$PNX" && exec java -Xmx1500m -Dfile.encoding=UTF-8 --add-opens java.base/java.lang=ALL-UNNAMED \
      --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED \
      -jar powernukkitx.jar --skip-setup --accept-license --language=eng --disable-ansi < /dev/null >> "$OUT/pnx.log" 2>&1) &
  pids+=($!)
}
wait_for() { for _ in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 1; done; return 1; }

: > "$OUT/pnx.log"
if [ ! -f "$PNX/pnx.yml" ]; then
  echo "== first PowerNukkitX start (generates pnx.yml)"
  start_pnx; wait_for "$OUT/pnx.log" "Done (" 180 || { echo "PowerNukkitX did not start"; tail -20 "$OUT/pnx.log"; exit 2; }
  kill "${pids[-1]}"; wait "${pids[-1]}" 2>/dev/null; : > "$OUT/pnx.log"
fi
sed -i -E "s/^(  port: ).*/\1$PNX_PORT/; s/^(  xboxAuth: ).*/\1false/" "$PNX/pnx.yml"
printf 'Tester\ntester\n' > "$PNX/ops.txt"

echo "== starting PowerNukkitX on udp/$PNX_PORT"
start_pnx; wait_for "$OUT/pnx.log" "Done (" 180 || { echo "PowerNukkitX did not start"; tail -20 "$OUT/pnx.log"; exit 2; }

cat > "$PROXY/config.yml" <<CFG
java:
  bind: 127.0.0.1
  port: $JAVA_PORT
  online-mode: false
bedrock:
  address: 127.0.0.1
  port: $PNX_PORT
  auth: offline
resource-packs:
  http-bind: 127.0.0.1
  http-port: $HTTP_PORT
CFG
echo "== starting the proxy on tcp/$JAVA_PORT"
(cd "$PROXY" && exec java -Djtb.data="$PROXY" -jar "$ROOT/build/libs/JavaToBedrock.jar" < /dev/null > "$OUT/proxy.log" 2>&1) &
pids+=($!)
wait_for "$OUT/proxy.log" "Ready in" 60 || { echo "The proxy did not start"; tail -40 "$OUT/proxy.log"; exit 2; }

echo "== headless Java client, scenario $SCENARIO, ${SECONDS_TO_PLAY}s"
java -cp "$CP" -Dhost=127.0.0.1 -Dport=$JAVA_PORT -Dname=Tester -Dseconds="$SECONDS_TO_PLAY" -Dscenario="$SCENARIO" \
    io.github.wyze3306.jtb.e2e.HeadlessJavaClient > "$OUT/client.log" 2>&1
status=$?
grep -E "^\[client\] (CHECK|Checks passed|JOIN|Disconnect)" "$OUT/client.log"
echo "== proxy warnings"; grep -E "WARN|ERROR" "$OUT/proxy.log" | sort | uniq -c | sort -rn | head -20
exit $status
