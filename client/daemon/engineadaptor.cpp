#include "engineadaptor.h"

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

QString EngineAdaptor::State() const { return m_e->state(); }
QString EngineAdaptor::PeerName() const { return m_e->peerName(); }
QString EngineAdaptor::SelfName() const { return m_e->selfName(); }
bool EngineAdaptor::PeerOnline() const { return m_e->peerOnline(); }
bool EngineAdaptor::AutoAnswer() const { return m_e->autoAnswer(); }
QString EngineAdaptor::LastError() const { return m_e->lastError(); }
