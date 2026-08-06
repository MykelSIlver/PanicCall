#ifndef CALLENGINE_H
#define CALLENGINE_H

// PanicCall client engine — proto 1 (see PROTOCOL.md on the server side).
//
// Owns: one QWebSocket to the relay, plus two GStreamer pipelines during a
// call:
//   send: pulsesrc ! ... ! opusenc ! appsink  -> 7-byte header -> WebSocket
//   recv: WebSocket -> strip header -> appsrc ! opusdec ! ... ! pulsesink
//
// Threading: GStreamer's appsink callback runs on a streaming thread. It
// only builds a QByteArray and emits audioFrameCaptured(); that signal is
// wired to sendAudioFrame() with Qt::QueuedConnection, so the actual
// QWebSocket call happens on the Qt main thread (QWebSocket is not
// thread-safe). Everything else runs on the main thread.
//
// Qt 5.6 compatible (SailfishOS): no qOverload, no 5.7+ API.

#include <QObject>
#include <QString>
#include <QByteArray>
#include <QElapsedTimer>
#include <QTimer>
#include <QtWebSockets/QWebSocket>

#include <gst/gst.h>
#include <gst/app/gstappsink.h>
#include <gst/app/gstappsrc.h>

#include "messagehistory.h"

class CallEngine : public QObject
{
    Q_OBJECT
    // "disconnected" | "connecting" | "idle" | "ringing" | "in_call"
    Q_PROPERTY(QString state READ state NOTIFY stateChanged)
    Q_PROPERTY(bool peerOnline READ peerOnline NOTIFY peerOnlineChanged)
    Q_PROPERTY(QString peerName READ peerName NOTIFY peerNameChanged)
    Q_PROPERTY(QString selfName READ selfName NOTIFY selfNameChanged)
    Q_PROPERTY(bool autoAnswer READ autoAnswer WRITE setAutoAnswer NOTIFY autoAnswerChanged)
    Q_PROPERTY(bool notifyPresence READ notifyPresence WRITE setNotifyPresence NOTIFY notifyPresenceChanged)
    Q_PROPERTY(QString lastError READ lastError NOTIFY lastErrorChanged)
    Q_PROPERTY(QObject *history READ historyObject CONSTANT)

public:
    explicit CallEngine(QObject *parent = nullptr);
    ~CallEngine();

    QString state() const { return m_state; }
    bool peerOnline() const { return m_peerOnline; }
    QString peerName() const { return m_peerName; }
    QString selfName() const { return m_selfName; }
    bool autoAnswer() const { return m_autoAnswer; }
    void setAutoAnswer(bool on);
    bool notifyPresence() const { return m_notifyPresence; }
    void setNotifyPresence(bool on);
    QString lastError() const { return m_lastError; }
    QObject *historyObject() const { return const_cast<MessageHistory *>(&m_history); }

    // Connect (and stay connected, with backoff) to the relay.
    Q_INVOKABLE void configure(const QString &url, const QString &token,
                               const QString &myName = QString());
    Q_INVOKABLE void disconnectFromServer();

    Q_INVOKABLE void startCall();   // caller: send "call" + stream immediately
    Q_INVOKABLE void answer();      // callee: open audio (manual accept)
    Q_INVOKABLE void hangup();
    // Returns the generated message id (Sailfish also logs to history
    // internally, unlike Android where the caller does that with this
    // return value -- the id is still returned here for QML/testing
    // convenience and cross-platform API symmetry).
    Q_INVOKABLE QString sendText(const QString &message);

    // App-level keepalive: tiny JSON frame to keep NAT/radio paths warm.
    // The server ignores unknown types by protocol rule.
    Q_INVOKABLE void sendKeepalivePing();

    // Daemon mode: on close 4003 (token taken over) keep reconnecting
    // instead of giving up. The daemon is the rightful owner of the
    // connection; a stale standalone app instance must lose this fight.
    void setPersistOnTakeover(bool on) { m_persistOnTakeover = on; }

signals:
    void stateChanged();
    void peerOnlineChanged();
    void peerNameChanged();
    void selfNameChanged();
    void autoAnswerChanged();
    void notifyPresenceChanged();
    void lastErrorChanged();
    void incomingCall(const QString &from);
    void textReceived(const QString &id, const QString &from, const QString &message);
    void textSent(const QString &id, bool queued);   // feedback for our own sendText()
    void textDelivered(const QString &id);   // the peer's client has processed our text
    // Internal: crosses from the GStreamer streaming thread to the Qt
    // main thread (queued). Do not connect from QML.
    void audioFrameCaptured(const QByteArray &frame);

private slots:
    void onConnected();
    void onDisconnected();
    void onTextMessage(const QString &msg);
    void onBinaryMessage(const QByteArray &msg);
    void sendAudioFrame(const QByteArray &frame);
    void tryConnect();
    void pollBus();

private:
    void setState(const QString &s);
    void setError(const QString &e);
    void scheduleReconnect();
    bool startAudio();
    void stopAudio();
    void startRingtone();     // synthesized SID-style ring, plays on "ringing"
    void stopRingtone();
    void advanceRingStep();   // timer-driven: steps freq/volume for the melody
    void playPresenceBlip(bool online);   // short one-shot chirp, no loop
    void advanceBlipStep();
    void sendJson(const QVariantMap &obj);
    static GstFlowReturn onNewSample(GstAppSink *sink, gpointer user);

    QWebSocket m_ws;
    QString m_url;
    QString m_token;
    QString m_myName;
    QString m_state;
    QString m_peerName;
    QString m_selfName;
    QString m_lastError;
    bool m_peerOnline;
    bool m_autoAnswer;
    bool m_wantConnected;
    bool m_persistOnTakeover;
    int m_backoffIdx;

    GstElement *m_sendPipe;
    GstElement *m_recvPipe;
    GstAppSrc  *m_appSrc;       // owned ref while m_recvPipe exists
    quint16     m_seq;          // written only from the streaming thread
    QElapsedTimer m_clock;
    QTimer      m_busPoll;      // surfaces GStreamer bus errors/warnings
    int         m_rxFrames;     // received audio frames (diagnostics)

    GstElement *m_ringPipe;     // synthesized ringtone (live audiotestsrc)
    GstElement *m_ringSrc;      // borrowed ref into m_ringPipe, for freq/volume
    QTimer      m_ringTimer;    // single-shot per step; re-armed with next duration
    int         m_ringStep;     // index into the melody step table
    bool        m_notifyPresence;

    GstElement *m_blipPipe;     // short one-shot presence chirp (own pipe, no
    GstElement *m_blipSrc;      // loop -- plays the online/offline chirp once
    MessageHistory m_history;
    QTimer      m_blipTimer;    // then tears itself down)
    int         m_blipStep;
    bool        m_blipIsOnline; // which of the two chirp tables is playing
};

#endif // CALLENGINE_H
