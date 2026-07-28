// PanicCall background daemon.
//
// Owns the CallEngine (WebSocket + audio) permanently, so calls arrive and
// auto-answer even when the UI app is closed. Exposes the engine on the
// session bus for the UI (which becomes a thin remote control), launches
// the UI when a call comes in, and logs METRIC lines so the real-device
// phase in October is a measuring campaign instead of a debugging one.
//
// Metrics grammar (grep METRIC in the journal):
//   METRIC alive uptime_s=<n> state=<s> reconnects=<n> wakeups=<n>
//   METRIC call_setup_ms=<n>        incoming_call -> audio open
//   METRIC wakeup n=<n>             KeepAlive fired (device builds only)

#include <QCoreApplication>
#include <QDBusConnection>
#include <QDBusError>
#include <QElapsedTimer>
#include <QProcess>
#include <QTimer>
#include <QDebug>

#include <gst/gst.h>

#include "callengine.h"
#include "engineadaptor.h"
#include "configsource.h"

#ifdef HAVE_KEEPALIVE
#include <keepalive/backgroundactivity.h>
#endif

static const char *kService = "com.mykelsilver.PanicCall";

int main(int argc, char *argv[])
{
    gst_init(&argc, &argv);
    QCoreApplication app(argc, argv);
    app.setOrganizationName(QStringLiteral("com.mykelsilver"));
    app.setApplicationName(QStringLiteral("PanicCall"));

    CallEngine engine;
    engine.setPersistOnTakeover(true);
    new EngineAdaptor(&engine, &engine);

    QDBusConnection bus = QDBusConnection::sessionBus();
    if (!bus.registerObject(QStringLiteral("/"), &engine)) {
        qWarning() << "paniccall-daemon: cannot register object:"
                   << bus.lastError().message();
        return 1;
    }
    if (!bus.registerService(QLatin1String(kService))) {
        qWarning() << "paniccall-daemon: service already registered"
                      " (another instance running?)";
        return 1;
    }

    // --- configuration: same dconf keys the app's settings write --------
    ConfigSource cfg;
    auto applyConfig = [&engine, &cfg]() {
        engine.setAutoAnswer(cfg.autoAnswer());
        if (!cfg.token().isEmpty() && !cfg.url().isEmpty())
            engine.configure(cfg.url(), cfg.token(), cfg.name());
        else
            qWarning() << "paniccall-daemon: not configured yet"
                          " (set URL and token in the app)";
    };
    QObject::connect(&cfg, &ConfigSource::changed, &engine, applyConfig);
    applyConfig();

    // --- metrics --------------------------------------------------------
    QElapsedTimer uptime;
    uptime.start();
    QElapsedTimer callSetup;
    int reconnects = -1;                // first "connecting" is not a re-
    int wakeups = 0;

    QObject::connect(&engine, &CallEngine::incomingCall, &engine,
        [&](const QString &from) {
            callSetup.start();
            qWarning() << "paniccall-daemon: incoming call from" << from;
            // Bring the UI to the front; audio does not depend on this.
            QProcess::startDetached(QStringLiteral("/usr/bin/invoker"),
                QStringList()
                    << QStringLiteral("--type=silica-qt5")
                    << QStringLiteral("--single-instance")
                    << QStringLiteral("/usr/bin/harbour-paniccall"));
        });

    QObject::connect(&engine, &CallEngine::stateChanged, &engine, [&]() {
        const QString s = engine.state();
        qWarning() << "paniccall-daemon: state" << s;
        if (s == QLatin1String("connecting"))
            ++reconnects;
        if (s == QLatin1String("in_call") && callSetup.isValid()) {
            qWarning().nospace()
                << "METRIC call_setup_ms=" << callSetup.elapsed();
            callSetup.invalidate();
        }
    });

    QTimer alive;
    alive.setInterval(5 * 60 * 1000);
    QObject::connect(&alive, &QTimer::timeout, &engine, [&]() {
        qWarning().nospace()
            << "METRIC alive uptime_s=" << uptime.elapsed() / 1000
            << " state=" << engine.state()
            << " reconnects=" << (reconnects < 0 ? 0 : reconnects)
            << " wakeups=" << wakeups;
    });
    alive.start();

    // --- KeepAlive: periodically wake from suspend to keep the socket ---
    // Compiled in only when nemo-keepalive-devel is present (device
    // targets). The emulator never suspends, so its absence there is fine.
#ifdef HAVE_KEEPALIVE
    BackgroundActivity *act = new BackgroundActivity(&app);
    act->setWakeupFrequency(BackgroundActivity::TwoAndHalfMinutes);
    QObject::connect(act, &BackgroundActivity::running, &engine, [&, act]() {
        ++wakeups;
        engine.sendKeepalivePing();
        qWarning().nospace() << "METRIC wakeup n=" << wakeups;
        act->wait();                    // schedule the next wakeup
    });
    act->wait();
    qWarning() << "paniccall-daemon: KeepAlive active (2.5 min wakeups)";
#else
    qWarning() << "paniccall-daemon: built without KeepAlive"
                  " (fine on the emulator; install nemo-keepalive-devel"
                  " in the target for device builds)";
#endif

    qWarning() << "paniccall-daemon: up, D-Bus service" << kService;
    return app.exec();
}
