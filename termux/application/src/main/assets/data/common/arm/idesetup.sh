#!/data/data/com.tom.rv2ide/files/usr/bin/bash

# Modified by Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
# ++ ndk support 


Color_Off='\033[0m'
Red='\033[0;31m'
Green='\033[0;32m'
Blue='\033[0;34m'
Orange="\e[38;5;208m"

yes='^[Yy][Ee]?[Ss]?$'

# Defualt values
arch=$(uname -m)
install_dir=$HOME
sdkver_org=34.0.4
with_cmdline=true
assume_yes=false
manifest="https://raw.githubusercontent.com/AndroidIDE-Rv2Official/androidide-rv2-tools/main/manifest.json"
pkgm="pkg"
pkg_curl="libcurl"
pkgs="jq tar wget"
jdk_version="17"
ndk_version="0"
ndk=false
# NDK package manifest. NDK packages are downloaded directly with curl/jq;
# the previous GitHub source is kept as a placeholder only and the 'acs' CLI
# is no longer used. Replace the URL once a new package source is published.
# ndk_manifest_url="https://raw.githubusercontent.com/AndroidCSOfficial/acs-build-system/refs/heads/main/acs-manifest.json"
ndk_manifest_url="https://gitee.com/huiywu/acs-ndk/raw/master/manifest.json"


# echo "This IDE SETUP FOR armeabi-v7a is hd=[$ndk_version] | from args=[$1, $2, $3]"

if apt update -y; then
    apt --fix-broken install -y
fi

npr() {
    local TAG="$Green[ NDK SETUP ]$Color_Off"
    echo -e "$TAG $1"
}

this_abort() {
    local TAG="$Red[ ERROR ]$Color_Off"
    echo -e "$TAG $1"
    exit 1
}

grep_prop() {
  local REGEX="s/^$1=//p"
  shift
  local FILES=$@
  [ -z "$FILES" ] && FILES='/system/build.prop'
  cat $FILES 2>/dev/null | dos2unix | sed -n "$REGEX" | head -n 1
}

grep_get_prop() {
  local result=$(grep_prop $@)
  if [ -z "$result" ]; then
    # Fallback to getprop
    getprop "$1"
  else
    echo $result
  fi
}

api_level_arch_detect() {
  API=$(grep_get_prop ro.build.version.sdk)
  ABI=$(grep_get_prop ro.product.cpu.abi)
  if [ "$ABI" = "arm64-v8a" ]; then
    ARCH=arm64
    ABI32=armeabi-v7a
    IS64BIT=true
  elif [ "$ABI" = "x86_64" ]; then
    ARCH=x64
    ABI32=x86
    IS64BIT=true
  elif [ "$ABI" = "armeabi-v7a" ]; then
    ARCH=arm
    ABI32=armeabi-v7a
    IS64BIT=false
  elif [ "$ABI" = "x86" ]; then
    ARCH=x86
    ABI32=x86
    IS64BIT=false
  elif [ "$ABI" = "riscv64" ]; then
    ARCH=riscv64
    ABI32=riscv32
    IS64BIT=true
  fi
}
    
ensure_ndk() {
    local ndkVersion="${1:-$ndk_version}"
    if [ -f "$HOME/android-sdk/ndk/$ndkVersion/ndk-build" ]; then
        return 0
    fi
    return 1
}

# Resolve the NDK version to install from the NDK package manifest. The latest
# stable version is returned; pre-release builds are only used when no stable
# build is available for the current ABI.
resolve_ndk_version() {
    local abi="$1"
    local package_id="android-native-kit"
    local manifest_file="$HOME/ndk-manifest.json"

    if [ -z "$ndk_manifest_url" ]; then
        return 1
    fi

    if [ ! -f "$manifest_file" ]; then
        curl -fsSL -o "$manifest_file" "$ndk_manifest_url" || return 1
    fi

    jq -r --arg abi "$abi" --arg id "$package_id" '
        [ .packages[] | select(.id == $id and .architecture == $abi) ]
        | sort_by(.version | split(".") | map(tonumber? // 0))
        | (map(select((.filename // "") | test("(^|[-_.])(beta|alpha|rc)[0-9]*([-_.]|$)"; "i") | not)) | last) // last
        | .version // empty
    ' "$manifest_file"
}

# Download, verify and extract an NDK package. Packages published on Gitee are
# split into multiple parts because of the platform size limit; every part is
# downloaded and concatenated before the archive is verified and extracted.
download_ndk() {
    local version="$1"
    local abi="$2"
    local package_id="android-native-kit"
    local manifest_file="$HOME/ndk-manifest.json"
    local ndk_root="$HOME/android-sdk/ndk"
    local work_dir="$HOME/ndk-download"
    local archive="$work_dir/ndk.tar.xz"

    if [ ! -f "$manifest_file" ]; then
        npr "Downloading NDK manifest..."
        curl -fsSL -o "$manifest_file" "$ndk_manifest_url" ||
            this_abort "Failed to download the NDK manifest from $ndk_manifest_url"
    fi

    local filename sha256
    filename=$(jq -r --arg id "$package_id" --arg abi "$abi" --arg version "$version" \
        '([ .packages[] | select(.id == $id and .architecture == $abi and .version == $version) ] | .[0] | .filename) // empty' \
        "$manifest_file")
    sha256=$(jq -r --arg id "$package_id" --arg abi "$abi" --arg version "$version" \
        '([ .packages[] | select(.id == $id and .architecture == $abi and .version == $version) ] | .[0] | .sha256) // empty' \
        "$manifest_file")

    if [ -z "$filename" ]; then
        this_abort "NDK $version (abi: $abi) was not found in the package manifest."
    fi

    local urls
    urls=$(jq -r --arg id "$package_id" --arg abi "$abi" --arg version "$version" \
        '[ .packages[] | select(.id == $id and .architecture == $abi and .version == $version) ] | .[0]
         | if ((.split_url // []) | length) > 0 then .split_url[] else (.url // empty) end' \
        "$manifest_file")

    if [ -z "$urls" ]; then
        this_abort "No download URL is available for NDK $version."
    fi

    echo
    npr "- Native Development Kit Manager -"
    npr "\t - Device abi: $abi"
    npr "\t - Artifact name: $filename"
    npr "\t - NDK version: $version"
    npr "\t - NDK sha256sum: ${sha256:0:8}..."
    echo

    rm -rf "$work_dir"
    mkdir -p "$work_dir"
    printf '%s\n' "$urls" > "$work_dir/urls.txt"

    local part_count
    part_count=$(grep -c . "$work_dir/urls.txt")

    if [ "$part_count" -gt 1 ]; then
        npr "Downloading $filename (split into $part_count parts)..."
        : > "$archive"
        local index=0 part
        while IFS= read -r part; do
            [ -z "$part" ] && continue
            index=$((index + 1))
            npr "Downloading part $index/$part_count..."
            curl -fL --http1.1 -o "$work_dir/part.$index" "$part" ||
                this_abort "Failed to download $part"
            cat "$work_dir/part.$index" >> "$archive"
            rm -f "$work_dir/part.$index"
        done < "$work_dir/urls.txt"
    else
        npr "Downloading $filename..."
        curl -fL --http1.1 -o "$archive" "$urls" || this_abort "Failed to download $filename"
    fi

    if [ -n "$sha256" ]; then
        npr "Verifying checksum..."
        local actual
        actual=$(sha256sum "$archive" | cut -d' ' -f1)
        if [ "$actual" != "$sha256" ]; then
            rm -rf "$work_dir"
            this_abort "Checksum mismatch for $filename (expected $sha256, got $actual)."
        fi
        npr "Checksum verified"
    fi

    mkdir -p "$ndk_root"
    npr "Extracting NDK..."
    tar xJf "$archive" -C "$work_dir" || this_abort "Failed to extract $filename"
    rm -f "$archive"

    # The archive contains a single top level directory (e.g. android-ndk-r29).
    local extracted
    extracted=$(find "$work_dir" -mindepth 1 -maxdepth 1 -type d | head -n 1)
    if [ -z "$extracted" ]; then
        this_abort "Unexpected NDK archive layout: no directory was extracted."
    fi

    rm -rf "$ndk_root/$version"
    mv "$extracted" "$ndk_root/$version" ||
        this_abort "Failed to install NDK $version to $ndk_root/$version"
    rm -rf "$work_dir"

    npr "NDK $version installed to $ndk_root/$version"
}

setup_ndk() {
    # The NDK is only installed when explicitly requested with -wn.
    if [ "$ndk" != "true" ]; then
        if [ "$ndk_version" != "0" ]; then
            npr "NDK version '$ndk_version' was specified with -n but -wn was not given."
            npr "Skipping NDK installation. Pass -wn to download and install the NDK."
        fi
        return 0
    fi

    api_level_arch_detect

    npr "Running environmental check..."

    local not_supported=("x86" "riscv64")
    for isSupportedArch in ${not_supported[@]}; do
        if [[ "$ARCH" == "$isSupportedArch" ]]; then
            this_abort "Unsupported architecture $ARCH ! ❌"
        fi
    done
    npr "Device architecture: $ARCH ✅"

    local resolved_version
    if [ "$ndk_version" != "0" ]; then
        # Explicit version requested with -n.
        resolved_version="$ndk_version"
    else
        resolved_version=$(resolve_ndk_version "$ABI")
    fi
    if [ -z "$resolved_version" ]; then
        this_abort "Failed to resolve an NDK version from the package manifest."
    fi

    npr "NDK version: $resolved_version"

    if ensure_ndk "$resolved_version"; then
        npr "NDK:$resolved_version Already installed! SETUP Skipped"
        return 0
    fi

    download_ndk "$resolved_version" "$ABI"
}

print_info() {
  # shellcheck disable=SC2059
  printf "${Blue}$1$Color_Off\n"
}

print_err() {
  # shellcheck disable=SC2059
  printf "${Red}$1$Color_Off\n"
}

print_warn() {
  # shellcheck disable=SC2059
  printf "${Orange}$1$Color_Off\n"
}

print_success() {
  # shellcheck disable=SC2059
  printf "${Green}$1$Color_Off\n"
}

is_yes() {

  msg=$1

  printf "%s ([y]es/[n]o): " "$msg"

  if [ "$assume_yes" == "true" ]; then
    ans="y"
    echo $ans
  else
    read -r ans
  fi

  if [[ "$ans" =~ $yes ]]; then
    return 0
  fi

  return 1
}

check_arg_value() {
  option_name="$1"
  arg_value="$2"
  if [[ -z "$arg_value" ]]; then
    print_err "No value provided for $option_name!" >&2
    exit 1
  fi
}

check_command_exists() {
  if command -v "$1" &>/dev/null; then
    return
  else
    print_err "Command '$1' not found!"
    exit 1
  fi
}

# shellcheck disable=SC2068
install_packages() {
  if [ "$assume_yes" == "true" ]; then
    $pkgm install $@ -y
  else
    $pkgm install $@
  fi
}

print_help() {
  echo "AndroidIDE build tools installer"
  echo "This script helps you easily install build tools in AndroidIDE."
  echo ""
  echo "Usage:"
  echo "${0} -s 34.0.4 -c -j 17"
  echo "This will install Android SDK 34.0.4 with command line tools and JDK 17."
  echo ""
  echo "Options :"
  echo "-i   Set the installation directory. Defaults to \$HOME."
  echo "-s   Android SDK version to download."
  echo "-c   Download Android SDK with command line tools."
  echo "-m   Manifest file URL. Defaults to 'manifest.json' in 'androidide-tools' GitHub repository."
  echo "-j   OpenJDK version to install. Values can be '17' or '21'"
  echo "-g   Install package: 'git'."
  echo "-o   Install package: 'openssh'."
  echo "-wn  Download and install the Android NDK."
  echo "-n   NDK version to install. Requires -wn. Defaults to the latest available version."
  echo "-y   Assume \"yes\" as answer to all prompts and run non-interactively."
  echo ""
  echo "For testing purposes:"
  echo "-a   CPU architecture. Extracted using 'uname -m' by default."
  echo "-p   Package manager. Defaults to 'pkg'."
  echo "-l   Name of curl package that will be installed before starting installation process. Defaults to 'libcurl'."
  echo ""
  echo "-h   Prints this message."
}

download_and_extract() {
  # Display name to use in print messages
  name=$1

  # URL to download from
  url=$2

  # Directory in which the downloaded archive will be extracted
  dir=$3

  # Destination path for downloading the file
  dest=$4
  
  if [ $# -ge 5 ]; then
    extract_with=$5
  else
    extract_with="xz"
  fi

  if [ ! -d "$dir" ]; then
    mkdir -p "$dir"
  fi

  cd "$dir"

  do_download=true
  if [ -f "$dest" ]; then
    name=$(basename "$dest")
    print_info "File ${name} already exists."
    if is_yes "Do you want to skip the download process?"; then
      do_download=false
    fi
    echo ""
  fi

  if [ "$do_download" = "true" ]; then
    print_info "Downloading $name..."
    curl -L -o "$dest" "$url" --http1.1
    print_success "$name has been downloaded."
    echo ""
  fi

  if [ ! -f "$dest" ]; then
    print_err "The downloaded file $name does not exist. Cannot proceed..."
    exit 1
  fi

  # Extract the downloaded archive
  if [[ "$extract_with" == "xz" ]]; then
    print_info "Extracting downloaded archive with xz..."
    tar xvJf "$dest" && print_info "Extracted successfully"
  else
    print_info "Extracting downloaded archive with unzip..."
    unzip "$dest" && print_info "Extracted successfully"
  fi
  echo ""

  # Delete the downloaded file
  rm -vf "$dest"

  # cd into the previous working directory
  cd -
}

download_comp() {
  nm=$1
  jq_query=$2
  mdir=$3
  dname=$4

  # Extract the Android SDK URL
  print_info "Extracting URL for $nm from manifest..."
  url=$(jq -r "${jq_query}" "$downloaded_manifest")
  print_success "Found URL: $url"
  echo ""

  # Download and extract the Android SDK build tools
  download_and_extract "$nm" "$url" "$mdir" "$mdir/$dname.tar.xz" "xz"
}

## NOTE!
## When adding more installation configuration arguments,
# add them in com.tom.rv2ide.models.IdeSetupArgument as well
while [ $# -gt 0 ]; do
  case $1 in
  -c | --with-cmdline-tools)
    shift
    with_cmdline=false
    ;;
  -g | --with-git)
    shift
    pkgs+=" git"
    ;;
  -o | --with-openssh)
    shift
    pkgs+=" openssh"
    ;;
  -wn | --with-ndk)
    shift
    ndk=true
    ;;
  -y | --assume-yes)
    shift
    assume_yes=true
    ;;
  -i | --install-dir)
    shift
    check_arg_value "--install-dir" "${1:-}"
    install_dir="$1"
    ;;
  -m | --manifest)
    shift
    check_arg_value "--manifest" "${1:-}"
    manifest="$1"
    ;;
  -s | --sdk)
    shift
    check_arg_value "--sdk" "${1:-}"
    sdkver_org="$1"
    ;;
  -j | --jdk)
    shift
    check_arg_value "--jdk" "${1:-}"
    jdk_version="$1"
    ;;
  -n | --ndk)
    shift
    check_arg_value "--ndk" "${1:-}"
    ndk_version="$1"
    ;;
  -a | --arch)
    shift
    check_arg_value "--arch" "${1:-}"
    arch="$1"
    ;;
  -p | --package-manager)
    shift
    check_arg_value "--package-manager" "${1:-}"
    pkgm="$1"
    ;;
  -l | --curl)
    shift
    check_arg_value "--curl" "${1:-}"
    pkg_curl="$1"
    ;;
  -h | --help)
    print_help
    exit 0
    ;;
  -*)
    echo "Invalid option: $1" >&2
    exit 1
    ;;
  *) break ;;
  esac
  shift
done

if [ "$arch" = "armv7l" ]; then
  arch="arm"
fi

# 64-bit CPU in 32-bit mode
if [ "$arch" = "armv8l" ]; then
  arch="arm"
fi

check_command_exists "$pkgm"

if [ "$jdk_version" == "21" ]; then
  print_warn "OpenJDK 21 support in AndroidIDE is experimental. It may or may not work properly."
  print_warn "Also, OpenJDK 21 is only supported in Gradle v8.4 and newer. Older versions of Gradle will NOT work!"
  if ! is_yes "Do you still want to install OpenJDK 21?"; then
    jdk_version="17"
    print_info "OpenJDK version has been reset to '17'"
  fi
fi

if [ "$jdk_version" != "17" ] && [ "$jdk_version" != "21" ]; then
  print_err "Invalid JDK version '$jdk_version'. Value can be '17' or '21'."
  exit 1
fi

sdk_version="_${sdkver_org//'.'/'_'}"

pkgs+=" $pkg_curl"

echo "------------------------------------------"
echo "Installation directory    : ${install_dir}"
echo "SDK version               : ${sdkver_org}"
echo "JDK version               : ${jdk_version}"
echo "With command line tools   : ${with_cmdline}"
echo "Extra packages            : ${pkgs}"
echo "CPU architecture          : ${arch}"
echo "------------------------------------------"

if ! is_yes "Confirm configuration"; then
  print_err "Aborting..."
  exit 1
fi

if [ ! -f "$install_dir" ]; then
  print_info "Installation directory does not exist. Creating directory..."
  mkdir -p "$install_dir"
fi

if [ ! command -v "$pkgm" ] &>/dev/null; then
  print_err "'$pkgm' command not found. Try installing 'termux-tools' and 'apt'."
  exit 1
fi

# Update repositories and packages
print_info "Update packages..."

$pkgm update
if [ "$assume_yes" == "true" ]; then
  $pkgm upgrade -y
else
  $pkgm upgrade
fi

# Install required packages
print_info "Installing required packages.."
# shellcheck disable=SC2086
install_packages $pkgs && print_success "Packages installed"
echo ""

# Download the manifest.json file
print_info "Downloading manifest file..."
downloaded_manifest="$install_dir/manifest.json"
curl -L -o "$downloaded_manifest" "$manifest" && print_success "Manifest file downloaded"
echo ""

# Install the Android SDK
download_comp "Android SDK" ".android_sdk" "$install_dir" "android-sdk"

#sdkmanager "build-tools;35.0.1" "build-tools;36.0.0"

# Install build tools
download_comp "Android SDK Build Tools" ".build_tools | .${arch} | .${sdk_version}" "$install_dir/android-sdk" "android-sdk-build-tools"

# Install platform tools
download_comp "Android SDK Platform Tools" ".platform_tools | .${arch} | .${sdk_version}" "$install_dir/android-sdk" "android-sdk-platform-tools"

if [ "$with_cmdline" = true ]; then
  # Install the Command Line tools
  download_comp "Command-line tools" ".cmdline_tools" "$install_dir/android-sdk" "cmdline-tools"
fi

# Install JDK
print_info "Installing package: 'openjdk-$jdk_version'"
install_packages "openjdk-$jdk_version" && print_info "JDK $jdk_version has been installed."

jdk_dir="$JAVA_HOME"

print_info "Updating ide-environment.properties..."
print_info "JAVA_HOME=$jdk_dir"
echo ""
props_dir="$SYSROOT/etc"
props="$props_dir/ide-environment.properties"

if [ ! -d "$props_dir" ]; then
  mkdir -p "$props_dir"
fi

if [ ! -e "$props" ]; then
  printf "JAVA_HOME=%s" "$jdk_dir" >"$props" && print_success "Properties file updated successfully!"
else
  if is_yes "$props file already exists. Would you like to overwrite it?"; then
    printf "JAVA_HOME=%s" "$jdk_dir" >"$props" && print_success "Properties file updated successfully!"
  else
    print_err "Manually edit $SYSROOT/etc/ide-environment.properties file and set JAVA_HOME and ANDROID_SDK_ROOT."
  fi
fi

setup_ndk

rm -vf "$downloaded_manifest"
print_success "Downloads completed. You are ready to go!"
