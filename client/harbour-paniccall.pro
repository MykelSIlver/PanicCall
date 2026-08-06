TARGET = harbour-paniccall

CONFIG += sailfishapp sailfishapp_i18n c++11
QT += websockets dbus
# NB: do not add 'link_pkgconfig' here — the sailfishapp feature adds it
# itself. Adding it manually runs link_pkgconfig before sailfishapp and
# -lsailfishapp drops off the link line (undefined references in main).
PKGCONFIG += gstreamer-1.0 gstreamer-app-1.0

# System notifications for incoming text messages (works whether the UI is
# foregrounded or not). Optional/guarded like mlite5 and keepalive in
# daemon.pro: falls back to a qWarning log line if the target lacks the
# -devel package (install with:
#   sfdk tools package-install <target> nemonotifications-qt5-devel
# if this feature is silently missing at runtime).
packagesExist(nemonotifications-qt5) {
    DEFINES += HAVE_NOTIFICATIONS
    PKGCONFIG += nemonotifications-qt5
}

SOURCES += src/harbour-paniccall.cpp \
    src/callengine.cpp \
    src/messagehistory.cpp \
    src/daemonproxy.cpp
HEADERS += src/callengine.h \
    src/messagehistory.h \
    src/daemonproxy.h

DISTFILES += qml/harbour-paniccall.qml \
    qml/pages/MainPage.qml \
    qml/pages/HistoryPage.qml \
    qml/cover/CoverPage.qml \
    rpm/harbour-paniccall.spec \
    harbour-paniccall.desktop

SAILFISHAPP_ICONS = 86x86 108x108 128x128 172x172

# English is the source language; add one line per extra language.
TRANSLATIONS += translations/harbour-paniccall.ts \
    translations/harbour-paniccall-nl.ts
