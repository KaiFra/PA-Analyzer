#!/usr/bin/env bash
# Turns the "What went wrong" part of a Gradle log into a GitHub error annotation.
log="${1:-gradle.log}"
[ -f "$log" ] || exit 0
details=$(awk '/What went wrong/{f=1} f{print} /^\* Try:/{f=0}' "$log" | grep -v '^\* Try:' | head -40)
[ -n "$details" ] || details=$(grep -E '^e: |error:|FAILED' "$log" | head -40)
[ -n "$details" ] || exit 0
escaped=$(printf '%s' "$details" | sed ':a;N;$!ba;s/%/%25/g;s/\r/%0D/g;s/\n/%0A/g')
echo "::error title=Gradle::$escaped"
