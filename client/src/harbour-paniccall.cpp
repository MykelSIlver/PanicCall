#include <QtQuick>
#include <QScopedPointer>
#include <QGuiApplication>
#include <QQuickView>
#include <QQmlContext>
#include <sailfishapp.h>
#include <gst/gst.h>
#include "callengine.h"

int main(int argc, char *argv[])
{
    gst_init(&argc, &argv);

    QScopedPointer<QGuiApplication> app(SailfishApp::application(argc, argv));
    QScopedPointer<QQuickView> view(SailfishApp::createView());

    CallEngine engine;
    view->rootContext()->setContextProperty("callEngine", &engine);
    view->setSource(SailfishApp::pathToMainQml());
    view->show();
    return app->exec();
}
