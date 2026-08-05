#include "callengine.h"

#include <QJsonDocument>
#include <QJsonObject>
#include <QUrl>
#include <QDebug>
#include <cstring>

#ifdef HAVE_NOTIFICATIONS
#include <notification.h>
#endif

namespace {
const int kProtoVersion = 1;
const int kBackoffMs[] = { 1000, 2000, 5000 };   // then stays at 5000
const quint8 kFrameAudio = 0x01;
const int kHeaderLen = 7;
// Frames to accumulate before playback starts (~80ms at 20ms/frame).
// Proven-safe: only shown to delay the START of playback, giving the
// decoder+pulsesink a head start; not a full mid-call jitter smoother.
const int kJitterPrebufFrames = 4;

// SID-style ringtone: an ascending C-major arpeggio (C5-E5-G5-C6), square
// wave, staccato -- classic 8-bit "phone's ringing" feel. Played via a
// single live audiotestsrc whose freq/volume we step through on a timer;
// deliberately NOT built from a finite concat-of-segments pipeline looped
// via seek: that was tried and gst_element_seek_simple() proved unreliable
// on a multi-source concat (returns FALSE, pipeline hangs). Stepping
// properties on one live source has no EOS/seek involved at all.
struct RingStep { double freq; double volume; int ms; };
const RingStep kRingMelody[] = {
    { 523,  0.5,  85 }, { 523, 0.0,  25 },   // C5
    { 659,  0.5,  85 }, { 659, 0.0,  25 },   // E5
    { 784,  0.5,  85 }, { 784, 0.0,  25 },   // G5
    { 1047, 0.5, 130 }, { 1047, 0.0, 170 },  // C6, then a rest before looping
};
const int kRingSteps = sizeof(kRingMelody) / sizeof(kRingMelody[0]);

// Short one-shot presence chirps -- clearly distinct from the 4-note
// ringtone arpeggio, so they can never be confused with an incoming call.
// Same {freq, volume, ms} shape as RingStep; kept as a separate BlipStep
// name for clarity even though the fields match.
struct BlipStep { double freq; double volume; int ms; };
const BlipStep kOnlineBlip[]  = { {700, 0.4, 55}, {1000, 0.4, 70} };  // rising
const BlipStep kOfflineBlip[] = { {1000, 0.4, 55}, {700, 0.4, 70} }; // falling
}

CallEngine::CallEngine(QObject *parent)
    : QObject(parent)
    , m_state(QStringLiteral("disconnected"))
    , m_peerOnline(false)
    , m_autoAnswer(true)
    , m_wantConnected(false)
    , m_persistOnTakeover(false)
    , m_backoffIdx(0)
    , m_sendPipe(nullptr)
    , m_recvPipe(nullptr)
    , m_appSrc(nullptr)
    , m_seq(0)
    , m_rxFrames(0)
    , m_ringPipe(nullptr)
    , m_ringSrc(nullptr)
    , m_ringStep(0)
    , m_notifyPresence(false)
    , m_blipPipe(nullptr)
    , m_blipSrc(nullptr)
    , m_blipStep(0)
    , m_blipIsOnline(false)
{
    connect(&m_ws, &QWebSocket::connected, this, &CallEngine::onConnected);
    connect(&m_ws, &QWebSocket::disconnected, this, &CallEngine::onDisconnected);
    connect(&m_ws, &QWebSocket::textMessageReceived,
            this, &CallEngine::onTextMessage);
    connect(&m_ws, &QWebSocket::binaryMessageReceived,
            this, &CallEngine::onBinaryMessage);
    // Cross-thread hop: GStreamer streaming thread -> Qt main thread.
    connect(this, &CallEngine::audioFrameCaptured,
            this, &CallEngine::sendAudioFrame, Qt::QueuedConnection);
    // Poll both pipeline buses so errors become visible instead of silent.
    m_busPoll.setInterval(200);
    connect(&m_busPoll, &QTimer::timeout, this, &CallEngine::pollBus);
    m_ringTimer.setSingleShot(true);
    connect(&m_ringTimer, &QTimer::timeout, this, &CallEngine::advanceRingStep);
    m_blipTimer.setSingleShot(true);
    connect(&m_blipTimer, &QTimer::timeout, this, &CallEngine::advanceBlipStep);
}

CallEngine::~CallEngine()
{
    m_wantConnected = false;
    stopAudio();
    stopRingtone();
    if (m_blipPipe) {
        if (m_blipSrc) gst_object_unref(m_blipSrc);
        gst_element_set_state(m_blipPipe, GST_STATE_NULL);
        gst_object_unref(m_blipPipe);
    }
    m_ws.abort();
}

void CallEngine::setAutoAnswer(bool on)
{
    if (m_autoAnswer == on)
        return;
    m_autoAnswer = on;
    emit autoAnswerChanged();
}

void CallEngine::setNotifyPresence(bool on)
{
    if (m_notifyPresence == on)
        return;
    m_notifyPresence = on;
    emit notifyPresenceChanged();
}

void CallEngine::configure(const QString &url, const QString &token,
                           const QString &myName)
{
    const QString name = myName.trimmed().left(32);
    // Idempotent: same parameters while (re)connected means there is
    // nothing to do. Without this, a stray configure (e.g. the UI opening
    // mid-call) would abort a live connection for no reason.
    if (url == m_url && token == m_token && name == m_myName
            && m_wantConnected
            && m_ws.state() != QAbstractSocket::UnconnectedState)
        return;
    m_url = url;
    m_token = token;
    m_myName = name;
    m_wantConnected = true;
    m_backoffIdx = 0;
    m_ws.abort();               // drop any half-open attempt, then reconnect
    tryConnect();
}

void CallEngine::disconnectFromServer()
{
    m_wantConnected = false;
    stopAudio();
    m_ws.close();
    setState(QStringLiteral("disconnected"));
}

void CallEngine::tryConnect()
{
    if (!m_wantConnected || m_url.isEmpty())
        return;
    setState(QStringLiteral("connecting"));
    m_ws.open(QUrl(m_url));
}

void CallEngine::onConnected()
{
    QVariantMap hello;
    hello.insert(QStringLiteral("type"), QStringLiteral("hello"));
    hello.insert(QStringLiteral("token"), m_token);
    hello.insert(QStringLiteral("proto"), kProtoVersion);
    if (!m_myName.isEmpty())
        hello.insert(QStringLiteral("name"), m_myName);
    sendJson(hello);
    // State flips to "idle" once the welcome arrives.
}

void CallEngine::onDisconnected()
{
    stopAudio();
    const int code = int(m_ws.closeCode());
    if (code == 4001) {
        setError(tr("Server does not know this token — check the configuration"));
        m_wantConnected = false;
    } else if (code == 4003) {
        setError(tr("Token was taken over by another device"));
        if (!m_persistOnTakeover)
            m_wantConnected = false;
        // daemon mode: keep m_wantConnected and reconnect — we will kick
        // the other party back until it gives up (it lacks this flag)
    } else if (code == 4004) {
        setError(tr("Protocol version not supported — app update required"));
        m_wantConnected = false;
    }
    setState(QStringLiteral("disconnected"));
    m_peerOnline = false;
    emit peerOnlineChanged();
    scheduleReconnect();
}

void CallEngine::scheduleReconnect()
{
    if (!m_wantConnected)
        return;
    const int n = int(sizeof(kBackoffMs) / sizeof(kBackoffMs[0]));
    const int ms = kBackoffMs[m_backoffIdx < n ? m_backoffIdx : n - 1];
    if (m_backoffIdx < n)
        ++m_backoffIdx;
    QTimer::singleShot(ms, this, SLOT(tryConnect()));
}

void CallEngine::sendJson(const QVariantMap &obj)
{
    const QJsonDocument doc(QJsonObject::fromVariantMap(obj));
    m_ws.sendTextMessage(QString::fromUtf8(doc.toJson(QJsonDocument::Compact)));
}

void CallEngine::onTextMessage(const QString &msg)
{
    const QJsonDocument doc = QJsonDocument::fromJson(msg.toUtf8());
    if (!doc.isObject())
        return;
    const QJsonObject o = doc.object();
    const QString type = o.value(QStringLiteral("type")).toString();

    if (type == QLatin1String("welcome")) {
        m_backoffIdx = 0;                       // connection proven good
        m_selfName = o.value(QStringLiteral("you")).toString();
        m_peerName = o.value(QStringLiteral("peer")).toString();
        m_peerOnline = o.value(QStringLiteral("peer_online")).toBool();
        emit selfNameChanged();
        emit peerNameChanged();
        emit peerOnlineChanged();
        setError(QString());
        setState(QStringLiteral("idle"));
    } else if (type == QLatin1String("peer_name")) {
        const QString n = o.value(QStringLiteral("name")).toString();
        if (!n.isEmpty() && n != m_peerName) {
            m_peerName = n;
            emit peerNameChanged();
        }
    } else if (type == QLatin1String("peer_online")) {
        m_peerOnline = true;
        emit peerOnlineChanged();
        if (m_notifyPresence)
            playPresenceBlip(true);
    } else if (type == QLatin1String("peer_offline")) {
        m_peerOnline = false;
        emit peerOnlineChanged();
        if (m_notifyPresence)
            playPresenceBlip(false);
    } else if (type == QLatin1String("incoming_call")) {
        const QString from = o.value(QStringLiteral("from")).toString();
        emit incomingCall(from);
        if (m_state == QLatin1String("idle")) {
            if (m_autoAnswer)
                answer();
            else
                setState(QStringLiteral("ringing"));
        }
    } else if (type == QLatin1String("hangup")) {
        stopAudio();
        if (m_state == QLatin1String("in_call")
                || m_state == QLatin1String("ringing"))
            setState(QStringLiteral("idle"));
    } else if (type == QLatin1String("error")) {
        const QString reason = o.value(QStringLiteral("reason")).toString();
        if (reason == QLatin1String("peer_offline"))
            setError(tr("%1 is not online").arg(m_peerName));
        else
            setError(reason);
    } else if (type == QLatin1String("text")) {
        const QString from = o.value(QStringLiteral("from")).toString();
        const QString msg = o.value(QStringLiteral("message")).toString();
        emit textReceived(from, msg);
#ifdef HAVE_NOTIFICATIONS
        // A system notification (not just an in-page label) so this is
        // seen whether the UI is foregrounded or not -- same reasoning as
        // why the ringtone/blip live in CallEngine rather than the QML:
        // the daemon owns this engine too, with no UI open at all.
        Notification n;
        n.setAppName(QStringLiteral("PanicCall"));
        n.setSummary(from);
        n.setBody(msg);
        n.setCategory(QStringLiteral("x-mykelsilver.paniccall.text"));
        n.publish();
#else
        qWarning() << "paniccall: text from" << from << ":" << msg
                   << "(built without notification support)";
#endif
    } else if (type == QLatin1String("text_sent")) {
        emit textSent(o.value(QStringLiteral("queued")).toBool());
    }
    // Unknown types: ignore (forward compatible).
}

void CallEngine::startCall()
{
    if (m_state != QLatin1String("idle"))
        return;
    QVariantMap call;
    call.insert(QStringLiteral("type"), QStringLiteral("call"));
    sendJson(call);
    // Protocol: caller streams immediately, no accept round-trip.
    if (startAudio())
        setState(QStringLiteral("in_call"));
}

void CallEngine::answer()
{
    if (startAudio())
        setState(QStringLiteral("in_call"));
}

void CallEngine::hangup()
{
    QVariantMap bye;
    bye.insert(QStringLiteral("type"), QStringLiteral("hangup"));
    sendJson(bye);
    stopAudio();
    if (m_state == QLatin1String("in_call") || m_state == QLatin1String("ringing"))
        setState(QStringLiteral("idle"));
}

void CallEngine::sendText(const QString &message)
{
    const QString trimmed = message.trimmed().left(200);
    if (trimmed.isEmpty())
        return;
    QVariantMap m;
    m.insert(QStringLiteral("type"), QStringLiteral("text"));
    m.insert(QStringLiteral("message"), trimmed);
    sendJson(m);
}

void CallEngine::sendKeepalivePing()
{
    if (m_ws.state() != QAbstractSocket::ConnectedState)
        return;
    QVariantMap ping;
    ping.insert(QStringLiteral("type"), QStringLiteral("ping"));
    sendJson(ping);
}

// ---------------------------------------------------------------- audio ---

bool CallEngine::startAudio()
{
    if (m_sendPipe)                             // already running
        return true;

    // PANICCALL_TESTTONE=1 substitutes a 440 Hz tone for the microphone —
    // handy in the emulator, where mic capture may not exist.
    const bool testTone = !qgetenv("PANICCALL_TESTTONE").isEmpty();
    const QString src = testTone
        ? QStringLiteral("audiotestsrc is-live=true wave=sine freq=440")
        : QStringLiteral("pulsesrc");

    const QString sendDesc = src + QStringLiteral(
        " ! audio/x-raw,format=S16LE,rate=48000,channels=1"
        " ! audioconvert ! audioresample"
        " ! opusenc bitrate=24000 frame-size=20 audio-type=voice inband-fec=true"
        " ! appsink name=snd sync=false");

    GError *err = nullptr;
    m_sendPipe = gst_parse_launch(sendDesc.toUtf8().constData(), &err);
    if (!m_sendPipe || err) {
        setError(QStringLiteral("send pipeline: %1")
                 .arg(err ? QString::fromUtf8(err->message) : QStringLiteral("?")));
        g_clear_error(&err);
        stopAudio();
        return false;
    }
    GstElement *sinkEl = gst_bin_get_by_name(GST_BIN(m_sendPipe), "snd");
    GstAppSinkCallbacks cb;
    std::memset(&cb, 0, sizeof cb);
    cb.new_sample = &CallEngine::onNewSample;
    gst_app_sink_set_callbacks(GST_APP_SINK(sinkEl), &cb, this, nullptr);
    gst_object_unref(sinkEl);

    // "jitterbuf" holds back the first frames until kJitterPrebufFrames
    // have arrived, giving the decoder+pulsesink a running start with some
    // slack already queued -- empirically verified to delay the start of
    // playback as intended. This does NOT smooth ongoing mid-call jitter
    // (that needs PTS-based clock sync, which testing showed is not a
    // simple property tweak -- see docs/CLIENT.md). max-size-* are set
    // generously so a genuine catch-up burst is never dropped.
    const QString recvDesc = QStringLiteral(
        "appsrc name=rcv ! queue name=jitterbuf min-threshold-buffers=%1"
        " max-size-buffers=0 max-size-bytes=0 max-size-time=500000000"
        " ! opusdec plc=true ! audioconvert"
        " ! audioresample ! queue ! pulsesink sync=false")
        .arg(kJitterPrebufFrames);
    m_recvPipe = gst_parse_launch(recvDesc.toUtf8().constData(), &err);
    if (!m_recvPipe || err) {
        setError(QStringLiteral("recv pipeline: %1")
                 .arg(err ? QString::fromUtf8(err->message) : QStringLiteral("?")));
        g_clear_error(&err);
        stopAudio();
        return false;
    }
    GstElement *srcEl = gst_bin_get_by_name(GST_BIN(m_recvPipe), "rcv");
    m_appSrc = GST_APP_SRC(srcEl);              // keep this ref until stopAudio
    GstCaps *caps = gst_caps_new_simple("audio/x-opus",
        "channel-mapping-family", G_TYPE_INT, 0,
        "rate", G_TYPE_INT, 48000,
        "channels", G_TYPE_INT, 1, nullptr);
    g_object_set(m_appSrc,
                 "caps", caps,
                 "format", GST_FORMAT_TIME,
                 "is-live", TRUE,
                 "do-timestamp", TRUE,          // stamp on arrival: v1 jitter plan
                 nullptr);
    gst_caps_unref(caps);

    m_seq = 0;
    m_rxFrames = 0;
    m_clock.start();
    gst_element_set_state(m_recvPipe, GST_STATE_PLAYING);
    gst_element_set_state(m_sendPipe, GST_STATE_PLAYING);
    m_busPoll.start();
    return true;
}

// Pop errors/warnings off both pipeline buses on the main thread. Without
// this, a failing pulsesrc/pulsesink dies silently and the app happily
// claims to be in a call.
void CallEngine::pollBus()
{
    GstElement *pipes[2] = { m_sendPipe, m_recvPipe };
    const char *names[2] = { "send", "recv" };
    for (int i = 0; i < 2; ++i) {
        if (!pipes[i])
            continue;
        GstBus *bus = gst_element_get_bus(pipes[i]);
        GstMessage *msg;
        while ((msg = gst_bus_pop_filtered(bus,
                GstMessageType(GST_MESSAGE_ERROR | GST_MESSAGE_WARNING)))) {
            GError *gerr = nullptr;
            gchar *dbg = nullptr;
            const bool isErr = GST_MESSAGE_TYPE(msg) == GST_MESSAGE_ERROR;
            if (isErr)
                gst_message_parse_error(msg, &gerr, &dbg);
            else
                gst_message_parse_warning(msg, &gerr, &dbg);
            const QString text = QStringLiteral("gst %1 [%2]: %3 (%4)")
                    .arg(QLatin1String(names[i]),
                         QLatin1String(GST_OBJECT_NAME(msg->src)),
                         gerr ? QString::fromUtf8(gerr->message) : QStringLiteral("?"),
                         dbg ? QString::fromUtf8(dbg) : QString());
            if (isErr)
                setError(text);
            else
                qWarning() << "paniccall:" << text;
            if (gerr)
                g_error_free(gerr);
            g_free(dbg);
            gst_message_unref(msg);
        }
        gst_object_unref(bus);
    }
}

void CallEngine::stopAudio()
{
    m_busPoll.stop();
    if (m_rxFrames > 0)
        qWarning() << "paniccall: call ended," << m_rxFrames
                   << "audio frames received";
    if (m_sendPipe) {
        gst_element_set_state(m_sendPipe, GST_STATE_NULL);
        gst_object_unref(m_sendPipe);
        m_sendPipe = nullptr;
    }
    if (m_appSrc) {
        gst_object_unref(m_appSrc);
        m_appSrc = nullptr;
    }
    if (m_recvPipe) {
        gst_element_set_state(m_recvPipe, GST_STATE_NULL);
        gst_object_unref(m_recvPipe);
        m_recvPipe = nullptr;
    }
}

// ------------------------------------------------------------- ringtone ---
// Synthesized SID-style arpeggio via ONE live audiotestsrc whose freq/volume
// we step on a timer (see kRingMelody). Deliberately not a finite pipeline
// looped by seeking: prototyping showed gst_element_seek_simple() failing
// unreliably on a multi-segment concat pipeline (returns FALSE, then the
// pipeline hangs) -- a live source with property steps has no EOS/seek in
// the loop at all, so there is nothing to fail.
void CallEngine::startRingtone()
{
    if (m_ringPipe)
        return;                                 // already ringing
    GError *err = nullptr;
    m_ringPipe = gst_parse_launch(
        "audiotestsrc name=ring is-live=true wave=square volume=0.0"
        " ! audioconvert ! audioresample ! pulsesink", &err);
    if (!m_ringPipe || err) {
        setError(QStringLiteral("ringtone: %1")
                 .arg(err ? QString::fromUtf8(err->message) : QStringLiteral("?")));
        g_clear_error(&err);
        m_ringPipe = nullptr;
        return;
    }
    m_ringSrc = gst_bin_get_by_name(GST_BIN(m_ringPipe), "ring");
    m_ringStep = 0;
    gst_element_set_state(m_ringPipe, GST_STATE_PLAYING);
    advanceRingStep();                           // apply step 0 immediately
}

void CallEngine::stopRingtone()
{
    m_ringTimer.stop();
    if (m_ringSrc) {
        gst_object_unref(m_ringSrc);
        m_ringSrc = nullptr;
    }
    if (m_ringPipe) {
        gst_element_set_state(m_ringPipe, GST_STATE_NULL);
        gst_object_unref(m_ringPipe);
        m_ringPipe = nullptr;
    }
}

// Fires once per melody step: set this step's freq/volume, then arm the
// timer for this step's duration before moving on. Wraps at the end of
// kRingMelody, so the arpeggio repeats for as long as we stay "ringing".
void CallEngine::advanceRingStep()
{
    if (!m_ringSrc)
        return;
    const RingStep &s = kRingMelody[m_ringStep];
    g_object_set(m_ringSrc, "freq", s.freq, "volume", s.volume, nullptr);
    m_ringStep = (m_ringStep + 1) % kRingSteps;
    m_ringTimer.start(s.ms);
}

// ---------------------------------------------------------- presence blip ---
// Short, one-shot "peer appeared / peer left" chirp -- opt-in, off by
// default. Reuses the exact live-audiotestsrc + timer-step mechanism
// proven for the ringtone, but with NO loop: advanceBlipStep() just calls
// stopBlip() after the last table entry instead of wrapping to 0. That is
// the only structural difference, so there is nothing new here to distrust.
void CallEngine::playPresenceBlip(bool online)
{
    if (m_blipPipe) {                            // a blip is already playing:
        m_blipTimer.stop();                      // replace it rather than
        if (m_blipSrc) gst_object_unref(m_blipSrc); // letting two overlap
        gst_element_set_state(m_blipPipe, GST_STATE_NULL);
        gst_object_unref(m_blipPipe);
        m_blipPipe = nullptr;
        m_blipSrc = nullptr;
    }
    GError *err = nullptr;
    m_blipPipe = gst_parse_launch(
        "audiotestsrc name=blip is-live=true wave=square volume=0.0"
        " ! audioconvert ! audioresample ! pulsesink", &err);
    if (!m_blipPipe || err) {
        qWarning() << "paniccall: presence blip pipeline failed:"
                   << (err ? err->message : "?");
        g_clear_error(&err);
        m_blipPipe = nullptr;
        return;
    }
    m_blipSrc = gst_bin_get_by_name(GST_BIN(m_blipPipe), "blip");
    m_blipIsOnline = online;
    m_blipStep = 0;
    gst_element_set_state(m_blipPipe, GST_STATE_PLAYING);
    advanceBlipStep();
}

void CallEngine::advanceBlipStep()
{
    if (!m_blipSrc)
        return;
    const BlipStep *table = m_blipIsOnline ? kOnlineBlip : kOfflineBlip;
    const int len = m_blipIsOnline
        ? int(sizeof(kOnlineBlip) / sizeof(kOnlineBlip[0]))
        : int(sizeof(kOfflineBlip) / sizeof(kOfflineBlip[0]));
    if (m_blipStep >= len) {                     // done: tear down, no loop
        gst_object_unref(m_blipSrc);
        m_blipSrc = nullptr;
        gst_element_set_state(m_blipPipe, GST_STATE_NULL);
        gst_object_unref(m_blipPipe);
        m_blipPipe = nullptr;
        return;
    }
    const BlipStep &s = table[m_blipStep];
    g_object_set(m_blipSrc, "freq", s.freq, "volume", s.volume, nullptr);
    m_blipStep++;
    m_blipTimer.start(s.ms);
}

// Runs on a GStreamer streaming thread — build the frame, hop to Qt thread.
GstFlowReturn CallEngine::onNewSample(GstAppSink *sink, gpointer user)
{
    CallEngine *self = static_cast<CallEngine *>(user);
    GstSample *sample = gst_app_sink_pull_sample(sink);
    if (!sample)
        return GST_FLOW_ERROR;
    GstBuffer *buf = gst_sample_get_buffer(sample);
    GstMapInfo map;
    if (buf && gst_buffer_map(buf, &map, GST_MAP_READ)) {
        const quint16 seq = self->m_seq++;
        const quint32 ts = quint32(self->m_clock.elapsed());
        char hdr[kHeaderLen];
        hdr[0] = char(kFrameAudio);
        hdr[1] = char((seq >> 8) & 0xff);
        hdr[2] = char(seq & 0xff);
        hdr[3] = char((ts >> 24) & 0xff);
        hdr[4] = char((ts >> 16) & 0xff);
        hdr[5] = char((ts >> 8) & 0xff);
        hdr[6] = char(ts & 0xff);
        QByteArray frame;
        frame.reserve(kHeaderLen + int(map.size));
        frame.append(hdr, kHeaderLen);
        frame.append(reinterpret_cast<const char *>(map.data), int(map.size));
        gst_buffer_unmap(buf, &map);
        emit self->audioFrameCaptured(frame);   // queued -> main thread
    }
    gst_sample_unref(sample);
    return GST_FLOW_OK;
}

// Main thread (queued from onNewSample).
void CallEngine::sendAudioFrame(const QByteArray &frame)
{
    if (m_state == QLatin1String("in_call")
            && m_ws.state() == QAbstractSocket::ConnectedState)
        m_ws.sendBinaryMessage(frame);
}

void CallEngine::onBinaryMessage(const QByteArray &msg)
{
    if (!m_appSrc)
        return;                                 // no active call: drop
    if (msg.size() < kHeaderLen + 1 || quint8(msg.at(0)) != kFrameAudio)
        return;                                 // unknown frame: ignore
    if (++m_rxFrames % 250 == 1)                // ~every 5 s of speech
        qWarning() << "paniccall: rx audio frames:" << m_rxFrames;
    const int n = msg.size() - kHeaderLen;
    GstBuffer *buf = gst_buffer_new_allocate(nullptr, gsize(n), nullptr);
    gst_buffer_fill(buf, 0, msg.constData() + kHeaderLen, gsize(n));
    gst_app_src_push_buffer(m_appSrc, buf);     // takes ownership of buf
}

// ------------------------------------------------------------- plumbing ---

void CallEngine::setState(const QString &s)
{
    if (m_state == s)
        return;
    const bool wasRinging = (m_state == QLatin1String("ringing"));
    const bool nowRinging  = (s == QLatin1String("ringing"));
    m_state = s;
    emit stateChanged();
    // Centralized here (rather than at each call site) so every path that
    // leaves "ringing" -- answer, hangup, peer hangup, disconnect -- is
    // covered automatically; a per-call-site approach risks silently
    // forgetting one and leaving the ringtone playing forever.
    if (nowRinging && !wasRinging)
        startRingtone();
    else if (wasRinging && !nowRinging)
        stopRingtone();
}

void CallEngine::setError(const QString &e)
{
    if (m_lastError == e)
        return;
    m_lastError = e;
    emit lastErrorChanged();
    if (!e.isEmpty())
        qWarning() << "paniccall:" << e;
}
