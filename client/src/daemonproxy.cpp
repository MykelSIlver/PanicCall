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
    , m_history(new HistoryProxy(&m_if, this))
    , m_state(QStringLiteral("disconnected"))
    , m_peerOnline(false)
    , m_autoAnswer(true)
    , m_notifyPresence(false)
    , m_notifyTextReceived(true)
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
    bus.connect(kService, kPath, kIface, "NotifyPresenceChanged",
                this, SLOT(onNotifyPresenceChanged(bool)));
    bus.connect(kService, kPath, kIface, "NotifyTextReceivedChanged",
                this, SLOT(onNotifyTextReceivedChanged(bool)));
    bus.connect(kService, kPath, kIface, "LastErrorChanged",
                this, SLOT(onLastErrorChanged(QString)));
    bus.connect(kService, kPath, kIface, "IncomingCall",
                this, SLOT(onIncomingCall(QString)));
    bus.connect(kService, kPath, kIface, "TextReceived",
                this, SLOT(onTextReceived(QString,QString,QString)));
    bus.connect(kService, kPath, kIface, "TextSent",
                this, SLOT(onTextSent(QString,bool)));
    bus.connect(kService, kPath, kIface, "TextDelivered",
                this, SLOT(onTextDelivered(QString)));
    // HistoryProxy refreshes itself whenever the daemon's own history
    // changes -- connected straight to it, not routed through a
    // DaemonProxy slot, since there is nothing else to do with this
    // signal here.
    bus.connect(kService, kPath, kIface, "HistoryChanged",
                m_history, SLOT(refresh()));

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
    QDBusReply<bool> np = m_if.call(QStringLiteral("NotifyPresence"));
    if (np.isValid()) onNotifyPresenceChanged(np.value());
    QDBusReply<bool> ntr = m_if.call(QStringLiteral("NotifyTextReceived"));
    if (ntr.isValid()) onNotifyTextReceivedChanged(ntr.value());
    QDBusReply<QString> le = m_if.call(QStringLiteral("LastError"));
    if (le.isValid()) onLastErrorChanged(le.value());
}

void DaemonProxy::setAutoAnswer(bool on)
{
    m_if.call(QStringLiteral("SetAutoAnswer"), on);
}

void DaemonProxy::setNotifyPresence(bool on)
{
    m_if.call(QStringLiteral("SetNotifyPresence"), on);
}

void DaemonProxy::setNotifyTextReceived(bool on)
{
    m_if.call(QStringLiteral("SetNotifyTextReceived"), on);
}

QString DaemonProxy::sendText(const QString &message)
{
    // Synchronous, like every other m_if.call() in this class (e.g.
    // startCall()/answer()/hangup()) -- consistent with the existing
    // pattern, and a local session-bus round trip to our own daemon is
    // fast enough that this hasn't needed to be async anywhere else here.
    QDBusReply<QString> r = m_if.call(QStringLiteral("SendText"), message);
    return r.isValid() ? r.value() : QString();
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

void DaemonProxy::onNotifyPresenceChanged(bool on)
{
    if (m_notifyPresence == on) return;
    m_notifyPresence = on;
    emit notifyPresenceChanged();
}

void DaemonProxy::onNotifyTextReceivedChanged(bool on)
{
    if (m_notifyTextReceived == on) return;
    m_notifyTextReceived = on;
    emit notifyTextReceivedChanged();
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

void DaemonProxy::onTextReceived(const QString &id, const QString &from,
                                 const QString &message)
{
    emit textReceived(id, from, message);
}

void DaemonProxy::onTextSent(const QString &id, bool queued)
{
    emit textSent(id, queued);
}

void DaemonProxy::onTextDelivered(const QString &id)
{
    emit textDelivered(id);
}
