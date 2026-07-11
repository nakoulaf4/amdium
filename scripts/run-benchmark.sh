#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/run-benchmark.sh --world PATH [options]

Options:
  --profile amdium|embeddium|both  Profile to run. Default: amdium
  --embeddium-jar PATH             Embeddium jar for embeddium profile; mapped Gradle cache jar is preferred in dev
  --duration SECONDS               Measurement duration after warmup. Default: 120
  --warmup SECONDS                 Warmup duration. Default: 30
  --phases JSON                    Multi-phase benchmark definition; overrides --warmup/--duration
  --repeat COUNT                   Repeat each selected profile in fresh client sessions. Default: 1
  --output DIR_OR_CSV              CSV output directory or file path. Default: build/benchmarks
  --label TEXT                     Free-form label written to CSV
  --fullscreen                     Run fullscreen. Default
  --window-size WIDTHxHEIGHT       Run windowed at a fixed resolution, e.g. 1280x720 or 1920x1080
  --java-home PATH                 JAVA_HOME for Gradle/Minecraft launch
  --no-gpu-timers                  Disable OpenGL timestamp queries
  --gpu-pass-timers                Enable detailed per-pass queries (high measurement overhead)
  --no-quit                        Keep Minecraft open after the measurement window
  --help                           Show this help

Example:
  scripts/run-benchmark.sh \
    --world "/path/to/saves/Minecraft Bench Mark" \
    --profile both \
    --embeddium-jar /path/to/embeddium.jar \
    --warmup 30 \
    --duration 180 \
    --output build/benchmarks
USAGE
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORLD_PATH=""
PROFILE="amdium"
EMBEDDIUM_JAR=""
DURATION="120"
WARMUP="30"
OUTPUT="build/benchmarks"
LABEL=""
FULLSCREEN="true"
WINDOW_WIDTH=""
WINDOW_HEIGHT=""
QUIT_ON_COMPLETE="true"
GPU_TIMERS="true"
GPU_PASS_TIMERS="false"
JAVA_HOME_ARG=""
PHASES_FILE=""
REPEAT="1"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --world)
      WORLD_PATH="${2:-}"
      shift 2
      ;;
    --profile)
      PROFILE="${2:-}"
      shift 2
      ;;
    --embeddium-jar)
      EMBEDDIUM_JAR="${2:-}"
      shift 2
      ;;
    --duration)
      DURATION="${2:-}"
      shift 2
      ;;
    --warmup)
      WARMUP="${2:-}"
      shift 2
      ;;
    --phases)
      PHASES_FILE="${2:-}"
      shift 2
      ;;
    --repeat)
      REPEAT="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT="${2:-}"
      shift 2
      ;;
    --label)
      LABEL="${2:-}"
      shift 2
      ;;
    --fullscreen)
      FULLSCREEN="true"
      WINDOW_WIDTH=""
      WINDOW_HEIGHT=""
      shift
      ;;
    --window-size)
      FULLSCREEN="false"
      if [[ "${2:-}" =~ ^([0-9]+)x([0-9]+)$ ]]; then
        WINDOW_WIDTH="${BASH_REMATCH[1]}"
        WINDOW_HEIGHT="${BASH_REMATCH[2]}"
      else
        echo "--window-size must use WIDTHxHEIGHT, e.g. 1920x1080" >&2
        exit 2
      fi
      shift 2
      ;;
    --java-home)
      JAVA_HOME_ARG="${2:-}"
      shift 2
      ;;
    --no-gpu-timers)
      GPU_TIMERS="false"
      shift
      ;;
    --gpu-pass-timers)
      GPU_PASS_TIMERS="true"
      shift
      ;;
    --no-quit)
      QUIT_ON_COMPLETE="false"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$WORLD_PATH" ]]; then
  echo "--world is required" >&2
  usage >&2
  exit 2
fi

if [[ ! -d "$WORLD_PATH" ]]; then
  echo "World path does not exist or is not a directory: $WORLD_PATH" >&2
  exit 2
fi

if [[ -n "$PHASES_FILE" ]]; then
  if [[ "$PHASES_FILE" != /* ]]; then
    PHASES_FILE="$ROOT_DIR/$PHASES_FILE"
  fi
  if [[ ! -f "$PHASES_FILE" ]]; then
    echo "Phase config does not exist or is not a file: $PHASES_FILE" >&2
    exit 2
  fi
fi

case "$PROFILE" in
  amdium|embeddium|both) ;;
  *)
    echo "--profile must be amdium, embeddium, or both" >&2
    exit 2
    ;;
esac

if [[ ! "$REPEAT" =~ ^[1-9][0-9]*$ ]]; then
  echo "--repeat must be a positive integer" >&2
  exit 2
fi

if [[ "$REPEAT" -gt 1 && "$OUTPUT" == *.csv ]]; then
  echo "--repeat greater than 1 requires --output to be a directory" >&2
  exit 2
fi

if [[ "$PROFILE" == "both" && "$OUTPUT" == *.csv ]]; then
  echo "--profile both requires --output to be a directory, not a CSV file" >&2
  exit 2
fi

if [[ -n "$JAVA_HOME_ARG" ]]; then
  export JAVA_HOME="$JAVA_HOME_ARG"
  export PATH="$JAVA_HOME_ARG/bin:$PATH"
fi

copy_world() {
  local source="$1"
  local target="$2"
  rm -rf "$target"
  mkdir -p "$(dirname "$target")"
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --delete "$source"/ "$target"/
  else
    mkdir -p "$target"
    cp -a "$source"/. "$target"/
  fi
}

prepare_mods() {
  local profile="$1"
  mkdir -p "$ROOT_DIR/run/mods"
  rm -f "$ROOT_DIR/run/mods/amdium-benchmark-embeddium.jar"
  if [[ "$profile" == "embeddium" ]]; then
    local jar
    jar="$(resolve_embeddium_jar)"
    cp "$jar" "$ROOT_DIR/run/mods/amdium-benchmark-embeddium.jar"
  fi
}

resolve_embeddium_jar() {
  if [[ -n "$EMBEDDIUM_JAR" && "$EMBEDDIUM_JAR" == *"_mapped_"* && -f "$EMBEDDIUM_JAR" ]]; then
    printf '%s\n' "$EMBEDDIUM_JAR"
    return
  fi

  local cached
  cached="$(find "$HOME/.gradle/caches/forge_gradle/deobf_dependencies/maven/modrinth/embeddium" \
    -name 'embeddium-*_mapped_*.jar' -type f 2>/dev/null | sort | tail -1 || true)"
  if [[ -n "$cached" && -f "$cached" ]]; then
    printf '%s\n' "$cached"
    return
  fi

  if [[ -n "$EMBEDDIUM_JAR" && -f "$EMBEDDIUM_JAR" ]]; then
    echo "Refusing to use a production Embeddium jar directly in ForgeGradle dev runtime: $EMBEDDIUM_JAR" >&2
    echo "Run compileJava once to populate ForgeGradle's deobf_dependencies cache, or pass a *_mapped_*.jar." >&2
    exit 2
  fi

  echo "No ForgeGradle-mapped Embeddium jar found." >&2
  echo "Run: bash ./gradlew compileJava --no-daemon" >&2
  exit 2
}

prepare_output() {
  local output_path="$1"
  if [[ "$output_path" == *.csv ]]; then
    mkdir -p "$(dirname "$output_path")"
  else
    mkdir -p "$output_path"
  fi
}

set_minecraft_option() {
  local file="$1"
  local key="$2"
  local value="$3"
  local tmp="${file}.tmp"
  mkdir -p "$(dirname "$file")"
  touch "$file"
  if grep -q "^${key}:" "$file"; then
    sed "s#^${key}:.*#${key}:${value}#" "$file" > "$tmp"
  else
    cp "$file" "$tmp"
    printf '%s:%s\n' "$key" "$value" >> "$tmp"
  fi
  mv "$tmp" "$file"
}

prepare_video_options() {
  local options_file="$ROOT_DIR/run/options.txt"
  set_minecraft_option "$options_file" "fullscreen" "$FULLSCREEN"
  set_minecraft_option "$options_file" "pauseOnLostFocus" "false"
  if [[ "$FULLSCREEN" == "true" ]]; then
    set_minecraft_option "$options_file" "overrideWidth" "0"
    set_minecraft_option "$options_file" "overrideHeight" "0"
  else
    set_minecraft_option "$options_file" "overrideWidth" "$WINDOW_WIDTH"
    set_minecraft_option "$options_file" "overrideHeight" "$WINDOW_HEIGHT"
  fi
}

absolute_path() {
  local path="$1"
  if [[ "$path" = /* ]]; then
    printf '%s\n' "$path"
  else
    printf '%s/%s\n' "$ROOT_DIR" "$path"
  fi
}

run_profile() {
  local profile="$1"
  local iteration="$2"
  local world_name="amdium-benchmark-${profile}"
  local world_target="$ROOT_DIR/run/saves/$world_name"
  local output_path
  output_path="$(absolute_path "$OUTPUT")"
  local phases_path=""
  local run_label="$LABEL"
  if [[ "$REPEAT" -gt 1 ]]; then
    run_label="${LABEL:+${LABEL}-}r${iteration}"
  fi
  if [[ -n "$PHASES_FILE" ]]; then
    phases_path="$(absolute_path "$PHASES_FILE")"
  fi

  if [[ "$PROFILE" == "both" && "$OUTPUT" != *.csv ]]; then
    output_path="$(absolute_path "$OUTPUT/$profile")"
  fi

  echo "Preparing world '$world_name' from: $WORLD_PATH"
  copy_world "$WORLD_PATH" "$world_target"
  prepare_mods "$profile"
  prepare_video_options
  prepare_output "$output_path"

  if [[ "$FULLSCREEN" == "true" ]]; then
    echo "Starting Minecraft benchmark profile=$profile warmup=${WARMUP}s duration=${DURATION}s display=fullscreen"
  else
    echo "Starting Minecraft benchmark profile=$profile warmup=${WARMUP}s duration=${DURATION}s display=${WINDOW_WIDTH}x${WINDOW_HEIGHT}"
  fi
  (
    cd "$ROOT_DIR"
    bash ./gradlew runClient --no-daemon \
      -Pamdium.benchmark.enabled=true \
      -Pamdium.benchmark.worldName="$world_name" \
      -Pamdium.benchmark.profile="$profile" \
      -Pamdium.benchmark.label="$run_label" \
      -Pamdium.benchmark.warmupSeconds="$WARMUP" \
      -Pamdium.benchmark.durationSeconds="$DURATION" \
      -Pamdium.benchmark.phases="$phases_path" \
      -Pamdium.benchmark.output="$output_path" \
      -Pamdium.benchmark.gpuTimers="$GPU_TIMERS" \
      -Pamdium.benchmark.gpuPassTimers="$GPU_PASS_TIMERS" \
      -Pamdium.benchmark.quitOnComplete="$QUIT_ON_COMPLETE"
  )
}

for ((iteration = 1; iteration <= REPEAT; iteration++)); do
  case "$PROFILE" in
    amdium)
      run_profile amdium "$iteration"
      ;;
    embeddium)
      run_profile embeddium "$iteration"
      ;;
    both)
      run_profile amdium "$iteration"
      run_profile embeddium "$iteration"
      ;;
  esac
done
