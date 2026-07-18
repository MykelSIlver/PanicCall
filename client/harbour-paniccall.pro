TARGET = harbour-paniccall

CONFIG += sailfishapp c++11
QT += websockets
# NB: do not add 'link_pkgconfig' here — the sailfishapp feature adds it
# itself. Adding it manually runs link_pkgconfig before sailfishapp and
# -lsailfishapp drops off the link line (undefined references in main).
PKGCONFIG += gstreamer-1.0 gstreamer-app-1.0

SOURCES += src/harbour-paniccall.cpp \
    src/callengine.cpp
HEADERS += src/callengine.h

DISTFILES += qml/harbour-paniccall.qml \
    qml/pages/MainPage.qml \
    qml/cover/CoverPage.qml \
    rpm/harbour-paniccall.spec \
    harbour-paniccall.desktop

SAILFISHAPP_ICONS = 86x86 108x108 128x128 172x172
