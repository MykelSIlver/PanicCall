Name:       harbour-paniccall
Summary:    One-button voice link between two paired Sailfish devices
Version:    0.1.0
Release:    1
License:    MIT
URL:        https://github.com/MykelSIlver/PanicCall
Source0:    %{name}-%{version}.tar.bz2
Requires:   sailfishsilica-qt5 >= 0.10.9
Requires:   nemo-qml-plugin-configuration-qt5
Requires:   qt5-qtwebsockets
BuildRequires:  pkgconfig(sailfishapp) >= 1.0.2
BuildRequires:  pkgconfig(Qt5Core)
BuildRequires:  pkgconfig(Qt5Qml)
BuildRequires:  pkgconfig(Qt5Quick)
BuildRequires:  pkgconfig(Qt5WebSockets)
BuildRequires:  pkgconfig(Qt5DBus)
BuildRequires:  pkgconfig(mlite5)
BuildRequires:  pkgconfig(gstreamer-1.0)
BuildRequires:  pkgconfig(gstreamer-app-1.0)
BuildRequires:  desktop-file-utils
BuildRequires:  qt5-qttools-linguist
# nemonotifications-qt5-devel is intentionally NOT a hard BuildRequires:
# text-message notifications degrade to a log line if it's absent from
# the target (see harbour-paniccall.pro). Install it for the real
# feature: sfdk tools package-install <target> nemonotifications-qt5-devel

%description
Walkie-talkie style panic call between two paired SailfishOS devices,
relayed through a self-hosted server (no WebRTC, no STUN/TURN).

%prep
%setup -q -n %{name}-%{version}

%build
%qmake5
%make_build
pushd daemon
%qmake5
%make_build
popd

%install
%qmake5_install
pushd daemon
%qmake5_install
popd

%files
%defattr(-,root,root,-)
%{_bindir}/%{name}
%{_bindir}/%{name}-daemon
/usr/lib/systemd/user/%{name}-daemon.service
%{_datadir}/%{name}
%{_datadir}/applications/%{name}.desktop
%{_datadir}/icons/hicolor/*/apps/%{name}.png
