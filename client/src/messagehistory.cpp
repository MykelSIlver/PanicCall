#include "messagehistory.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLocale>
#include <QStandardPaths>
#include <QDebug>

namespace {
const int kMaxEntries = 200;   // a quick-message log, not an unbounded
                                // chat archive -- kept small on purpose.

// English-only, pinned locale (not the device locale) so month names
// don't vary unexpectedly -- same choice made on the Android side.
QString formatTimestamp(qint64 ms)
{
    QLocale en(QLocale::English, QLocale::UnitedStates);
    return en.toString(QDateTime::fromMSecsSinceEpoch(ms), QStringLiteral("MMM d hh:mm AP"));
}
}

MessageHistory::MessageHistory(QObject *parent)
    : QObject(parent)
{
    load();
}

QString MessageHistory::filePath() const
{
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(dir);          // first run: the directory may not exist yet
    return dir + QStringLiteral("/message_history.json");
}

void MessageHistory::addSent(const QString &id, const QString &peer, const QString &message)
{
    QVariantMap e;
    e.insert(QStringLiteral("id"), id);
    e.insert(QStringLiteral("direction"), QStringLiteral("sent"));
    e.insert(QStringLiteral("peer"), peer);
    e.insert(QStringLiteral("message"), message);
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    e.insert(QStringLiteral("timestampMs"), now);
    e.insert(QStringLiteral("timestampText"), formatTimestamp(now));
    e.insert(QStringLiteral("status"), QStringLiteral("pending"));
    m_entries.prepend(e);         // newest first
    trimAndPublish();
}

void MessageHistory::addReceived(const QString &id, const QString &peer, const QString &message)
{
    QVariantMap e;
    e.insert(QStringLiteral("id"), id);
    e.insert(QStringLiteral("direction"), QStringLiteral("received"));
    e.insert(QStringLiteral("peer"), peer);
    e.insert(QStringLiteral("message"), message);
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    e.insert(QStringLiteral("timestampMs"), now);
    e.insert(QStringLiteral("timestampText"), formatTimestamp(now));
    e.insert(QStringLiteral("status"), QStringLiteral("delivered"));  // meaningless
    m_entries.prepend(e);          // for received, but harmless to set
    trimAndPublish();
}

void MessageHistory::markStatus(const QString &id, const QString &status)
{
    for (int i = 0; i < m_entries.size(); ++i) {
        QVariantMap e = m_entries.at(i).toMap();
        if (e.value(QStringLiteral("id")).toString() == id
                && e.value(QStringLiteral("direction")).toString() == QLatin1String("sent")) {
            e.insert(QStringLiteral("status"), status);
            m_entries[i] = e;
            save();
            emit entriesChanged();
            return;
        }
    }
    // No matching SENT row (e.g. a delivered-ack for a message this
    // device never sent) -- nothing to update, not an error.
}

void MessageHistory::clear()
{
    m_entries.clear();
    save();
    emit entriesChanged();
}

void MessageHistory::trimAndPublish()
{
    while (m_entries.size() > kMaxEntries)
        m_entries.removeLast();
    save();
    emit entriesChanged();
}

void MessageHistory::load()
{
    m_entries.clear();
    QFile f(filePath());
    if (!f.exists()) {
        return;                    // first run: nothing to restore, not an error
    }
    if (!f.open(QIODevice::ReadOnly)) {
        qWarning() << "paniccall: could not open message history file:" << f.errorString();
        return;
    }
    const QByteArray raw = f.readAll();
    f.close();
    QJsonParseError err;
    const QJsonDocument doc = QJsonDocument::fromJson(raw, &err);
    if (err.error != QJsonParseError::NoError || !doc.isArray()) {
        // Corrupt/unreadable history must never crash the app; start
        // fresh -- same "warn and continue, never fatal" spirit as the
        // relay's own corrupt-pending-state handling.
        qWarning() << "paniccall: message history file is corrupt, starting fresh:"
                   << err.errorString();
        return;
    }
    const QJsonArray arr = doc.array();
    for (const QJsonValue &v : arr) {
        if (v.isObject())
            m_entries.append(v.toObject().toVariantMap());
    }
}

void MessageHistory::save()
{
    QJsonArray arr;
    for (const QVariant &v : m_entries)
        arr.append(QJsonObject::fromVariantMap(v.toMap()));
    QFile f(filePath());
    if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        // Best-effort: losing a history write is far less bad than
        // crashing the call/text feature over it.
        qWarning() << "paniccall: could not write message history file:" << f.errorString();
        return;
    }
    f.write(QJsonDocument(arr).toJson(QJsonDocument::Compact));
    f.close();
}
