#include "engineadaptor.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>

EngineAdaptor::EngineAdaptor(CallEngine *engine, QObject *parent)
    : QDBusAbstractAdaptor(parent)
    , m_e(engine)
{
    connect(m_e, &CallEngine::stateChanged, this,
            [this]() { emit StateChanged(m_e->state()); });
    connect(m_e, &CallEngine::peerNameChanged, this,
            [this]() { emit PeerNameChanged(m_e->peerName()); });
    connect(m_e, &CallEngine::selfNameChanged, this,
            [this]() { emit SelfNameChanged(m_e->selfName()); });
    connect(m_e, &CallEngine::peerOnlineChanged, this,
            [this]() { emit PeerOnlineChanged(m_e->peerOnline()); });
    connect(m_e, &CallEngine::autoAnswerChanged, this,
            [this]() { emit AutoAnswerChanged(m_e->autoAnswer()); });
    connect(m_e, &CallEngine::lastErrorChanged, this,
            [this]() { emit LastErrorChanged(m_e->lastError()); });
    connect(m_e, &CallEngine::incomingCall, this,
            [this](const QString &from) { emit IncomingCall(from); });
    connect(m_e, &CallEngine::textReceived, this,
            [this](const QString &id, const QString &from, const QString &message) {
                emit TextReceived(id, from, message);
            });
    connect(m_e, &CallEngine::textSent, this,
            [this](const QString &id, bool queued) { emit TextSent(id, queued); });
    connect(m_e, &CallEngine::textDelivered, this,
            [this](const QString &id) { emit TextDelivered(id); });
    connect(m_e, &CallEngine::historyChanged, this,
            [this]() { emit HistoryChanged(); });
    connect(m_e, &CallEngine::notifyPresenceChanged, this,
            [this]() { emit NotifyPresenceChanged(m_e->notifyPresence()); });
    connect(m_e, &CallEngine::notifyTextReceivedChanged, this,
            [this]() { emit NotifyTextReceivedChanged(m_e->notifyTextReceived()); });
}

void EngineAdaptor::Configure(const QString &url, const QString &token,
                              const QString &name)
{
    m_e->configure(url, token, name);
}

void EngineAdaptor::StartCall() { m_e->startCall(); }
void EngineAdaptor::Answer() { m_e->answer(); }
void EngineAdaptor::Hangup() { m_e->hangup(); }
void EngineAdaptor::SetAutoAnswer(bool on) { m_e->setAutoAnswer(on); }

QString EngineAdaptor::SendText(const QString &message)
{
    return m_e->sendText(message);
}

QString EngineAdaptor::History() const
{
    QJsonArray arr;
    for (const QVariant &v : m_e->historyEntries())
        arr.append(QJsonObject::fromVariantMap(v.toMap()));
    return QString::fromUtf8(QJsonDocument(arr).toJson(QJsonDocument::Compact));
}

void EngineAdaptor::ClearHistory() { m_e->clearHistory(); }

bool EngineAdaptor::NotifyPresence() const { return m_e->notifyPresence(); }
void EngineAdaptor::SetNotifyPresence(bool on) { m_e->setNotifyPresence(on); }
bool EngineAdaptor::NotifyTextReceived() const { return m_e->notifyTextReceived(); }
void EngineAdaptor::SetNotifyTextReceived(bool on) { m_e->setNotifyTextReceived(on); }

QString EngineAdaptor::State() const { return m_e->state(); }
QString EngineAdaptor::PeerName() const { return m_e->peerName(); }
QString EngineAdaptor::SelfName() const { return m_e->selfName(); }
bool EngineAdaptor::PeerOnline() const { return m_e->peerOnline(); }
bool EngineAdaptor::AutoAnswer() const { return m_e->autoAnswer(); }
QString EngineAdaptor::LastError() const { return m_e->lastError(); }
