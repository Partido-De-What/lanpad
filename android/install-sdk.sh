#!/bin/sh
# Install the packages required by this app into a project-local SDK by default.
# Set ANDROID_HOME before running to use an existing SDK location instead.
set -eu
android_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
sdk_root=${ANDROID_HOME:-"$android_dir/../.android-sdk"}

if [ "$(uname -s)" != Linux ] || [ "$(uname -m)" != x86_64 ]; then
    echo "This installer supports Linux x86_64. Use Android Studio's SDK Manager on other systems." >&2
    exit 1
fi
for tool in java curl unzip sha256sum; do
    command -v "$tool" >/dev/null || { echo "Install $tool before continuing." >&2; exit 1; }
done
mkdir -p "$sdk_root/cmdline-tools"
sdk_root=$(CDPATH= cd -- "$sdk_root" && pwd)
sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$sdkmanager" ]; then
    if [ -e "$sdk_root/cmdline-tools/latest" ]; then
        echo "An incomplete command-line tools installation exists at $sdk_root/cmdline-tools/latest." >&2
        exit 1
    fi
    temporary_dir=$(mktemp -d "$sdk_root/.setup.XXXXXX")
    trap 'rm -rf "$temporary_dir"' EXIT HUP INT TERM
    # Pin Google's official Linux archive and verify its published SHA-256.
    archive_url=https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
    archive_sha=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
    if ! curl --fail --location --connect-timeout 10 --max-time 600 \
        "$archive_url" --output "$temporary_dir/tools.zip"; then
        echo "SDK download failed. Run this script in a terminal with internet access." >&2
        exit 1
    fi
    printf '%s  %s\n' "$archive_sha" "$temporary_dir/tools.zip" | sha256sum --check --status
    unzip -q "$temporary_dir/tools.zip" -d "$temporary_dir/unpacked"
    mv "$temporary_dir/unpacked/cmdline-tools" "$sdk_root/cmdline-tools/latest"
    rm -rf "$temporary_dir"
    trap - EXIT HUP INT TERM
fi

# Google presents license terms here; answer the prompts before package installation.
"$sdkmanager" --sdk_root="$sdk_root" --licenses
"$sdkmanager" --sdk_root="$sdk_root" --install \
    "platform-tools" "platforms;android-36" "build-tools;36.0.0"

# Verify actual package contents before reporting completion or configuring Gradle.
test -f "$sdk_root/platforms/android-36/android.jar"
test -x "$sdk_root/build-tools/36.0.0/aapt2"
test -x "$sdk_root/platform-tools/adb"
if [ ! -e "$android_dir/local.properties" ]; then
    property_path=$(printf '%s' "$sdk_root" | sed 's/\\/\\\\/g; s/:/\\:/g')
    printf 'sdk.dir=%s\n' "$property_path" > "$android_dir/local.properties"
else
    printf 'Existing local.properties retained. Ensure sdk.dir points to %s\n' "$sdk_root"
fi
printf 'Android SDK requirements installed at %s\n' "$sdk_root"
