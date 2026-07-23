#ifndef DAEMONPROXY_H
#define DAEMONPROXY_H

// Drop-in replacement for CallEngine on the QML side when the background
// daemon owns the real engine. Exposes the identical property/invokable
// surface, so the QML does not know or care which one it is talking to.

#include <QObject>
#include <QString>
#include <QDBusInterface>

class DaemonProxy : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString state READ state NOTIFY stateChanged)
    Q_PROPERTY(bool peerOnline READ peerOnline NOTIFY peerOnlineChanged)
    Q_PROPERTY(QString peerName READ peerName NOTIFY peerNameChanged)
    Q_PROPERTY(QString selfName READ selfName NOTIFY selfNameChanged)
    Q_PROPERTY(bool autoAnswer READ autoAnswer WRITE setAutoAnswer NOTIFY autoAnswerChanged)
    Q_PROPERTY(QString lastError READ lastError NOTIFY lastErrorChanged)

public:
    explicit DaemonProxy(QObject *parent = nullptr);

    QString state() const { return m_state; }
    bool peerOnline() const { return m_peerOnline; }
    QString peerName() const { return m_peerName; }
    QString selfName() const { return m_selfName; }
    bool autoAnswer() const { return m_autoAnswer; }
    void setAutoAnswer(bool on);
    QString lastError() const { return m_lastError; }

    Q_INVOKABLE void configure(const QString &url, const QString &token,
                               const QString &myName = QString());
    Q_INVOKABLE void startCall();
    Q_INVOKABLE void answer();
    Q_INVOKABLE void hangup();
    Q_INVOKABLE void disconnectFromServer() {}   // daemon owns the link

signals:
    void stateChanged();
    void peerOnlineChanged();
    void peerNameChanged();
    void selfNameChanged();
    void autoAnswerChanged();
    void lastErrorChanged();
    void incomingCall(const QString &from);

private slots:
    void onStateChanged(const QString &s);
    void onPeerNameChanged(const QString &n);
    void onSelfNameChanged(const QString &n);
    void onPeerOnlineChanged(bool on);
    void onAutoAnswerChanged(bool on);
    void onLastErrorChanged(const QString &e);
    void onIncomingCall(const QString &from);

private:
    QDBusInterface m_if;
    QString m_state;
    QString m_peerName;
    QString m_selfName;
    QString m_lastError;
    bool m_peerOnline;
    bool m_autoAnswer;
};

#endif // DAEMONPROXY_H
