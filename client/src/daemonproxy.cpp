#include "daemonproxy.h"

#include <QDBusConnection>
#include <QDBusReply>

namespace {
const char *kService = "com.mykelsilver.PanicCall";
const char *kPath = "/";
const char *kIface = "com.mykelsilver.PanicCall.Engine";
}

DaemonProxy::DaemonProxy(QObject *parent)
    : QObject(parent)
    , m_if(QLatin1String(kService), QLatin1String(kPath),
           QLatin1String(kIface), QDBusConnection::sessionBus())
    , m_state(QStringLiteral("disconnected"))
    , m_peerOnline(false)
    , m_autoAnswer(true)
{
    QDBusConnection bus = QDBusConnection::sessionBus();
    bus.connect(kService, kPath, kIface, "StateChanged",
                this, SLOT(onStateChanged(QString)));
    bus.connect(kService, kPath, kIface, "PeerNameChanged",
                this, SLOT(onPeerNameChanged(QString)));
    bus.connect(kService, kPath, kIface, "SelfNameChanged",
                this, SLOT(onSelfNameChanged(QString)));
    bus.connect(kService, kPath, kIface, "PeerOnlineChanged",
                this, SLOT(onPeerOnlineChanged(bool)));
    bus.connect(kService, kPath, kIface, "AutoAnswerChanged",
                this, SLOT(onAutoAnswerChanged(bool)));
    bus.connect(kService, kPath, kIface, "LastErrorChanged",
                this, SLOT(onLastErrorChanged(QString)));
    bus.connect(kService, kPath, kIface, "IncomingCall",
                this, SLOT(onIncomingCall(QString)));

    // Initial snapshot so the UI opens with live values.
    QDBusReply<QString> s = m_if.call(QStringLiteral("State"));
    if (s.isValid()) onStateChanged(s.value());
    QDBusReply<QString> pn = m_if.call(QStringLiteral("PeerName"));
    if (pn.isValid()) onPeerNameChanged(pn.value());
    QDBusReply<QString> sn = m_if.call(QStringLiteral("SelfName"));
    if (sn.isValid()) onSelfNameChanged(sn.value());
    QDBusReply<bool> po = m_if.call(QStringLiteral("PeerOnline"));
    if (po.isValid()) onPeerOnlineChanged(po.value());
    QDBusReply<bool> aa = m_if.call(QStringLiteral("AutoAnswer"));
    if (aa.isValid()) onAutoAnswerChanged(aa.value());
    QDBusReply<QString> le = m_if.call(QStringLiteral("LastError"));
    if (le.isValid()) onLastErrorChanged(le.value());
}

void DaemonProxy::setAutoAnswer(bool on)
{
    m_if.call(QStringLiteral("SetAutoAnswer"), on);
}

void DaemonProxy::configure(const QString &url, const QString &token,
                            const QString &myName)
{
    m_if.call(QStringLiteral("Configure"), url, token, myName);
}

void DaemonProxy::startCall() { m_if.call(QStringLiteral("StartCall")); }
void DaemonProxy::answer() { m_if.call(QStringLiteral("Answer")); }
void DaemonProxy::hangup() { m_if.call(QStringLiteral("Hangup")); }

void DaemonProxy::onStateChanged(const QString &s)
{
    if (m_state == s) return;
    m_state = s;
    emit stateChanged();
}

void DaemonProxy::onPeerNameChanged(const QString &n)
{
    if (m_peerName == n) return;
    m_peerName = n;
    emit peerNameChanged();
}

void DaemonProxy::onSelfNameChanged(const QString &n)
{
    if (m_selfName == n) return;
    m_selfName = n;
    emit selfNameChanged();
}

void DaemonProxy::onPeerOnlineChanged(bool on)
{
    if (m_peerOnline == on) return;
    m_peerOnline = on;
    emit peerOnlineChanged();
}

void DaemonProxy::onAutoAnswerChanged(bool on)
{
    if (m_autoAnswer == on) return;
    m_autoAnswer = on;
    emit autoAnswerChanged();
}

void DaemonProxy::onLastErrorChanged(const QString &e)
{
    if (m_lastError == e) return;
    m_lastError = e;
    emit lastErrorChanged();
}

void DaemonProxy::onIncomingCall(const QString &from)
{
    emit incomingCall(from);
}
