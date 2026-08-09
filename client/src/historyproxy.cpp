#include "historyproxy.h"

#include <QDBusInterface>
#include <QDBusReply>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonValue>
#include <QDebug>

HistoryProxy::HistoryProxy(QDBusInterface *iface, QObject *parent)
    : QObject(parent)
    , m_if(iface)
{
    refresh();   // initial snapshot so the history page opens populated
}

void HistoryProxy::refresh()
{
    QDBusReply<QString> r = m_if->call(QStringLiteral("History"));
    if (!r.isValid()) {
        qWarning() << "paniccall: History() D-Bus call failed:"
                   << r.error().message();
        return;
    }
    QJsonParseError err;
    const QJsonDocument doc = QJsonDocument::fromJson(r.value().toUtf8(), &err);
    if (err.error != QJsonParseError::NoError || !doc.isArray()) {
        qWarning() << "paniccall: History() returned unparseable JSON:"
                   << err.errorString();
        return;
    }
    m_entries.clear();
    const QJsonArray arr = doc.array();
    for (const QJsonValue &v : arr) {
        if (v.isObject())
            m_entries.append(v.toObject().toVariantMap());
    }
    emit entriesChanged();
}

void HistoryProxy::clear()
{
    m_if->call(QStringLiteral("ClearHistory"));
    // No explicit refresh() here: the daemon's HistoryChanged signal
    // (connected by DaemonProxy's constructor) fires as a result and
    // triggers refresh() for us -- same pattern as every other piece of
    // daemon state in this proxy layer.
}
