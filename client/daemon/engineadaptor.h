#ifndef ENGINEADAPTOR_H
#define ENGINEADAPTOR_H

// Exposes CallEngine on the session bus as
// com.mykelsilver.PanicCall.Engine at path "/".
//
// The service name deliberately matches the app's Sailjail identity
// (OrganizationName com.mykelsilver + ApplicationName PanicCall), so the
// sandboxed UI app is allowed to talk to it.

#include <QDBusAbstractAdaptor>
#include "callengine.h"

class EngineAdaptor : public QDBusAbstractAdaptor
{
    Q_OBJECT
    Q_CLASSINFO("D-Bus Interface", "com.mykelsilver.PanicCall.Engine")

public:
    explicit EngineAdaptor(CallEngine *engine, QObject *parent);

public slots:
    void Configure(const QString &url, const QString &token,
                   const QString &name);
    void StartCall();
    void Answer();
    void Hangup();
    void SetAutoAnswer(bool on);

    QString State() const;
    QString PeerName() const;
    QString SelfName() const;
    bool PeerOnline() const;
    bool AutoAnswer() const;
    QString LastError() const;

signals:
    void StateChanged(const QString &state);
    void PeerNameChanged(const QString &name);
    void SelfNameChanged(const QString &name);
    void PeerOnlineChanged(bool online);
    void AutoAnswerChanged(bool on);
    void LastErrorChanged(const QString &error);
    void IncomingCall(const QString &from);

private:
    CallEngine *m_e;
};

#endif // ENGINEADAPTOR_H
