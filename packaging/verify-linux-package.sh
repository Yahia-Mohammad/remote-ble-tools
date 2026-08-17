#!/usr/bin/env sh
set -eu

if [ "$#" -ne 4 ]; then
  echo "Usage: $0 <deb|rpm> <package-path> <version> <amd64|arm64>" >&2
  exit 64
fi

format=$1
package=$2
version=$3
architecture=$4

case "$format:$architecture" in
  deb:amd64) expected_rpm_arch=x86_64 ;;
  deb:arm64) expected_rpm_arch=aarch64 ;;
  rpm:amd64) expected_rpm_arch=x86_64 ;;
  rpm:arm64) expected_rpm_arch=aarch64 ;;
  *) echo "Unsupported package target: $format $architecture" >&2; exit 64 ;;
esac

case "$format" in
  deb)
    image='debian:13@sha256:34cd9e9fd437c0a095ec39cb2e73422c9f30821b0d0848ed74fd0d43bae4d958'
    docker run --rm -v "$(cd "$(dirname "$package")" && pwd):/packages:ro" "$image" sh -ceux '
      package=/packages/$1
      version=$2
      arch=$3
      # nFPM folds `release: "1"` from packaging/nfpm.yaml into the single Debian version field,
      # so the package declares `0.1.0-1` where the caller passes `0.1.0`. The rpm branch below
      # reads VERSION and RELEASE separately and needs no such adjustment.
      deb_version=$version-1
      dpkg-deb -I "$package" | grep -Eq "^ Package: remoteble$"
      dpkg-deb -I "$package" | grep -Eq "^ Version: $deb_version$"
      dpkg-deb -I "$package" | grep -Eq "^ Architecture: $arch$"
      dpkg-deb -I "$package" | grep -Eq "^ Depends: .*libc6"
      dpkg-deb -c "$package" | grep -q "./usr/bin/remoteble$"
      dpkg-deb -c "$package" | grep -q "./usr/bin/rble$"
      dpkg-deb -c "$package" | grep -q "./usr/share/man/man1/remoteble.1$"
      dpkg-deb -c "$package" | grep -q "./usr/share/remoteble/skills/remoteble/SKILL.md$"
      ! dpkg-deb -c "$package" | grep -Eq "(./etc/|systemd|\.service$)"
      apt-get update
      apt-get install -y "$package"
      test "$(dpkg --print-architecture)" = "$arch"
      test "$(dpkg-query -W -f="\${Version}" remoteble)" = "$deb_version"
      remoteble --version
      rble --version
      ! ldd /usr/bin/remoteble | grep -q "not found"
      test -f /usr/share/man/man1/remoteble.1
      test -f /usr/share/bash-completion/completions/remoteble
      test -f /usr/share/zsh/site-functions/_remoteble
      test -f /usr/share/fish/vendor_completions.d/remoteble.fish
      test -f /usr/share/remoteble/skills/remoteble/SKILL.md
      test -f /usr/share/doc/remoteble/LICENSE
      test -f /usr/share/doc/remoteble/NOTICE
      test -f /usr/share/doc/remoteble/sbom.json
      apt-get remove -y remoteble
      ! test -e /usr/bin/remoteble
      ! test -e /usr/bin/rble
    ' sh "$(basename "$package")" "$version" "$architecture"
    ;;
  rpm)
    image='fedora:43@sha256:762d73ba1c455232b0272c5d445a34f36c4b9f421cbc05ce8102552325b6a222'
    docker run --rm -v "$(cd "$(dirname "$package")" && pwd):/packages:ro" "$image" sh -ceux '
      package=/packages/$1
      version=$2
      arch=$3
      rpm_arch=$4
      rpm -qp --qf "%{NAME} %{VERSION} %{RELEASE} %{ARCH}\n" "$package" | grep -Fx "remoteble $version 1 $rpm_arch"
      rpm -qpR "$package" | grep -Eq "(^|[[:space:]])glibc"
      rpm -qpl "$package" | grep -Fx /usr/bin/remoteble
      rpm -qpl "$package" | grep -Fx /usr/bin/rble
      rpm -qpl "$package" | grep -Fx /usr/share/man/man1/remoteble.1
      rpm -qpl "$package" | grep -Fx /usr/share/remoteble/skills/remoteble/SKILL.md
      ! rpm -qpl "$package" | grep -Eq "^/etc/|systemd|\.service$"
      dnf install -y "$package"
      test "$(uname -m)" = "$rpm_arch"
      rpm -q --qf "%{VERSION}-%{RELEASE}\n" remoteble | grep -Fx "$version-1"
      remoteble --version
      rble --version
      ! ldd /usr/bin/remoteble | grep -q "not found"
      test -f /usr/share/man/man1/remoteble.1
      test -f /usr/share/bash-completion/completions/remoteble
      test -f /usr/share/zsh/site-functions/_remoteble
      test -f /usr/share/fish/vendor_completions.d/remoteble.fish
      test -f /usr/share/remoteble/skills/remoteble/SKILL.md
      test -f /usr/share/doc/remoteble/LICENSE
      test -f /usr/share/doc/remoteble/NOTICE
      test -f /usr/share/doc/remoteble/sbom.json
      dnf remove -y remoteble
      ! test -e /usr/bin/remoteble
      ! test -e /usr/bin/rble
    ' sh "$(basename "$package")" "$version" "$architecture" "$expected_rpm_arch"
    ;;
esac
