#include <QtQuick>
#include <QScopedPointer>
#include <QGuiApplication>
#include <QQuickView>
#include <QQmlContext>
#include <QDBusConnection>
#include <QDBusConnectionInterface>
#include <sailfishapp.h>
#include <gst/gst.h>
#include "callengine.h"
#include "daemonproxy.h"

int main(int argc, char *argv[])
{
    gst_init(&argc, &argv);

    QScopedPointer<QGuiApplication> app(SailfishApp::application(argc, argv));
    QScopedPointer<QQuickView> view(SailfishApp::createView());

    // If the background daemon owns the engine, become a thin remote
    // control; otherwise run the engine embedded (standalone mode).
    QObject *engine = nullptr;
    QDBusConnectionInterface *ifc = QDBusConnection::sessionBus().interface();
    const bool daemonUp = ifc && ifc->isServiceRegistered(
        QStringLiteral("com.mykelsilver.PanicCall"));
    if (daemonUp)
        engine = new DaemonProxy(app.data());
    else
        engine = new CallEngine(app.data());

    view->rootContext()->setContextProperty("callEngine", engine);
    view->rootContext()->setContextProperty("daemonMode", daemonUp);
    view->setSource(SailfishApp::pathToMainQml());
    view->show();
    return app->exec();
}
