#!/usr/bin/env bash
#
# Runs, one step at a time, the Gradle checks the GitHub workflows run on a push to main, so a push only goes out
# once they pass here. Each step is its own Gradle run with its own log, so a failure in one cannot hide in the
# output of another.
#
# Usage:
#   scripts/check-before-push.sh            run every step; on success, record that HEAD passed
#   scripts/check-before-push.sh lint tests run only the named steps (records nothing)
#
# The record (.git/ci-local-passed) is what the pre-push hook checks; install the hook with
# scripts/install-git-hooks.sh. Every step must pass on a clean working tree for HEAD to be recorded.
#
# Steps, and the workflow each mirrors:
#   spotless      quality-checks.yml, quality-markdown.yml (spotlessCheck covers the Markdown formatter too)
#   detekt        quality-checks.yml (detekt, which covers the multiplatform source sets too)
#   detekttypes   quality-checks.yml (detektAll, the type-resolved rules in config/detekt/detekt-type-resolution.yml)
#   dependencies  quality-checks.yml (dependencyGuard)
#   konsist       quality-checks.yml (:quality:konsist:test, which testsOnCi leaves out)
#   assemble      build-android.yml (app-common, both apps, all variants, unminified)
#   badging       build-android.yml (the apps' badging checks)
#   catalog       build-android.yml (the design system catalog app)
#   tests         build-android.yml (testsOnCi)
#   lint          build-android.yml (lint, every module)

set -u

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT" || exit 1

LOG_DIR="$ROOT/build/ci-local"
STAMP="$(git rev-parse --git-common-dir)/ci-local-passed"
mkdir -p "$LOG_DIR"

ALL_STEPS=(spotless detekt detekttypes dependencies konsist assemble badging catalog tests lint)

# The components build has no daemon JVM setting of its own, so Gradle starts it on JAVA_HOME, and its build plugin
# needs Java 21, which CI sets up. Prints JAVA_HOME when it is Java 21 or newer, else the Java 21 that the main build's
# daemon setting had Gradle download, else nothing.
java21_home() {
    if [ -n "${JAVA_HOME:-}" ] && grep -qE '^JAVA_VERSION="(2[1-9]|[3-9][0-9])' "$JAVA_HOME/release" 2>/dev/null; then
        echo "$JAVA_HOME"
        return
    fi
    for dir in "$HOME"/.gradle/jdks/*-21-*/; do
        if [ -f "$dir/provisioned.ok" ]; then
            echo "${dir%/}"
            return
        fi
    done
}

run_step() {
    case "$1" in
        spotless) ./gradlew spotlessCheck ;;
        detekt) ./gradlew detekt ;;
        detekttypes) ./gradlew detektAll --continue ;;
        dependencies) ./gradlew dependencyGuard ;;
        konsist) ./gradlew :quality:konsist:test ;;
        assemble) ./gradlew :app-common:assemble :app-k9mail:assemble :app-thunderbird:assemble -Pminify=false ;;
        badging)
            ./gradlew :app-k9mail:checkFossReleaseBadging :app-k9mail:checkFullReleaseBadging \
                :app-thunderbird:checkFossBetaBadging :app-thunderbird:checkFossDailyBadging \
                :app-thunderbird:checkFossReleaseBadging :app-thunderbird:checkFullBetaBadging \
                :app-thunderbird:checkFullDailyBadging :app-thunderbird:checkFullReleaseBadging \
                -x assemble -x build
            ;;
        catalog)
            local java21
            java21="$(java21_home)"
            JAVA_HOME="${java21:-${JAVA_HOME:-}}" ./gradlew -p components :ui:catalog:assembleDebug
            ;;
        tests) ./gradlew testsOnCi --parallel ;;
        lint) ./gradlew lint ;;
        *)
            echo "Unknown step: $1 (steps: ${ALL_STEPS[*]})" >&2
            return 2
            ;;
    esac
}

# What a failed step's log says went wrong, without the thousands of lines around it.
summarize_failure() {
    grep -E "> Task .* FAILED|^e: |Error:|error:|What went wrong|tests completed, [0-9]+ failed|FAILED$|Lint found" "$1" |
        grep -v "warning:" | head -25
}

if [ "$#" -gt 0 ]; then
    STEPS=("$@")
    RECORD=false
else
    STEPS=("${ALL_STEPS[@]}")
    RECORD=true
fi

HEAD_SHA="$(git rev-parse HEAD)"
if $RECORD && [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "The working tree has changes that are not committed; the result would not be recorded for HEAD."
    echo "Commit or stash them first, or name the steps to run."
    exit 1
fi

FAILED=()
for step in "${STEPS[@]}"; do
    log="$LOG_DIR/$step.log"
    start=$(date +%s)
    printf '%-13s ' "$step"
    if run_step "$step" >"$log" 2>&1; then
        echo "passed ($(($(date +%s) - start))s)"
    else
        echo "FAILED ($(($(date +%s) - start))s) - log: $log"
        summarize_failure "$log" | sed 's/^/    /'
        FAILED+=("$step")
    fi
done

if [ "${#FAILED[@]}" -gt 0 ]; then
    echo
    echo "Failed: ${FAILED[*]}"
    exit 1
fi

if $RECORD; then
    if [ "$(git rev-parse HEAD)" != "$HEAD_SHA" ] || [ -n "$(git status --porcelain --untracked-files=no)" ]; then
        echo "HEAD or the working tree changed while the checks ran; nothing recorded. Run again."
        exit 1
    fi
    echo "$HEAD_SHA" >"$STAMP"
    echo
    echo "All steps passed for $HEAD_SHA; recorded for the pre-push hook."
fi
