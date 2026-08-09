#ifndef DAEMONPROXY_H
#define DAEMONPROXY_H

// Drop-in replacement for CallEngine on the QML side when the background
// daemon owns the real engine. Exposes the identical property/invokable
// surface, so the QML does not know or care which one it is talking to.
//
// THIS PROMISE MUST BE MAINTAINED BY HAND: nothing enforces it at compile
// time. It was broken once already -- CallEngine gained notifyPresence,
// notifyTextReceived, sendText, and history across several later commits
// without this class (or EngineAdaptor) being updated to match, so
// callEngine.sendText(...) and callEngine.history.entries silently
// failed (QML TypeError) whenever the app ran against the daemon instead
// of standalone. Whenever CallEngine grows a new Q_PROPERTY, Q_INVOKABLE,
// or signal that QML calls or binds to, mirror it here AND in
// EngineAdaptor (daemon/engineadaptor.h) in the same change -- not later.

#include <QObject>
#include <QString>
#include <QDBusInterface>

#include "historyproxy.h"

class DaemonProxy : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString state READ state NOTIFY stateChanged)
    Q_PROPERTY(bool peerOnline READ peerOnline NOTIFY peerOnlineChanged)
    Q_PROPERTY(QString peerName READ peerName NOTIFY peerNameChanged)
    Q_PROPERTY(QString selfName READ selfName NOTIFY selfNameChanged)
    Q_PROPERTY(bool autoAnswer READ autoAnswer WRITE setAutoAnswer NOTIFY autoAnswerChanged)
    Q_PROPERTY(bool notifyPresence READ notifyPresence WRITE setNotifyPresence NOTIFY notifyPresenceChanged)
    Q_PROPERTY(bool notifyTextReceived READ notifyTextReceived WRITE setNotifyTextReceived NOTIFY notifyTextReceivedChanged)
    Q_PROPERTY(QString lastError READ lastError NOTIFY lastErrorChanged)
    Q_PROPERTY(QObject *history READ history CONSTANT)

public:
    explicit DaemonProxy(QObject *parent = nullptr);

    QString state() const { return m_state; }
    bool peerOnline() const { return m_peerOnline; }
    QString peerName() const { return m_peerName; }
    QString selfName() const { return m_selfName; }
    bool autoAnswer() const { return m_autoAnswer; }
    void setAutoAnswer(bool on);
    bool notifyPresence() const { return m_notifyPresence; }
    void setNotifyPresence(bool on);
    bool notifyTextReceived() const { return m_notifyTextReceived; }
    void setNotifyTextReceived(bool on);
    QString lastError() const { return m_lastError; }
    QObject *history() const { return m_history; }

    Q_INVOKABLE void configure(const QString &url, const QString &token,
                               const QString &myName = QString());
    Q_INVOKABLE void startCall();
    Q_INVOKABLE void answer();
    Q_INVOKABLE void hangup();
    Q_INVOKABLE void disconnectFromServer() {}   // daemon owns the link
    Q_INVOKABLE QString sendText(const QString &message);

signals:
    void stateChanged();
    void peerOnlineChanged();
    void peerNameChanged();
    void selfNameChanged();
    void autoAnswerChanged();
    void notifyPresenceChanged();
    void notifyTextReceivedChanged();
    void lastErrorChanged();
    void incomingCall(const QString &from);
    void textReceived(const QString &id, const QString &from, const QString &message);
    void textSent(const QString &id, bool queued);
    void textDelivered(const QString &id);

private slots:
    void onStateChanged(const QString &s);
    void onPeerNameChanged(const QString &n);
    void onSelfNameChanged(const QString &n);
    void onPeerOnlineChanged(bool on);
    void onAutoAnswerChanged(bool on);
    void onNotifyPresenceChanged(bool on);
    void onNotifyTextReceivedChanged(bool on);
    void onLastErrorChanged(const QString &e);
    void onIncomingCall(const QString &from);
    void onTextReceived(const QString &id, const QString &from, const QString &message);
    void onTextSent(const QString &id, bool queued);
    void onTextDelivered(const QString &id);

private:
    QDBusInterface m_if;
    HistoryProxy *m_history;
    QString m_state;
    QString m_peerName;
    QString m_selfName;
    QString m_lastError;
    bool m_peerOnline;
    bool m_autoAnswer;
    bool m_notifyPresence;
    bool m_notifyTextReceived;
};

#endif // DAEMONPROXY_H
