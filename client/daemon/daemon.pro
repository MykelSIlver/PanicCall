TEMPLATE = app
TARGET = harbour-paniccall-daemon

QT = core network websockets dbus
CONFIG += c++11 link_pkgconfig
PKGCONFIG += gstreamer-1.0 gstreamer-app-1.0

# dconf config (SFOS); without it the env-var fallback compiles in
packagesExist(mlite5) {
    DEFINES += HAVE_MLITE
    PKGCONFIG += mlite5
}
# suspend wakeups (device targets); harmless to miss on the emulator
packagesExist(keepalive) {
    DEFINES += HAVE_KEEPALIVE
    PKGCONFIG += keepalive
}

INCLUDEPATH += ../src
SOURCES += daemon_main.cpp engineadaptor.cpp ../src/callengine.cpp
HEADERS += engineadaptor.h configsource.h ../src/callengine.h

target.path = /usr/bin
service.files = harbour-paniccall-daemon.service
service.path = /usr/lib/systemd/user
INSTALLS += target service
