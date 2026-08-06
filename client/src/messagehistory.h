#ifndef MESSAGEHISTORY_H
#define MESSAGEHISTORY_H

// Local, per-device message history: what YOU sent (with delivery status)
// and what you received. Persisted as a small JSON file under
// QStandardPaths::AppDataLocation -- deliberately NOT stored on the relay
// (see docs/PROTOCOL.md: the relay keeps no message content beyond a
// single in-flight pending message). Survives app restarts; does not
// survive an uninstall, same tradeoff already accepted for the relay's
// own durability design, and each device only ever sees its own side of
// the conversation.
//
// Owned directly by CallEngine (a plain member, not a separately-wired
// context property) so it works identically whether CallEngine lives in
// the daemon or the standalone app -- same reasoning as the ringtone/
// presence-blip pipelines already being CallEngine members.

#include <QObject>
#include <QString>
#include <QVariantList>
#include <QVariantMap>

class MessageHistory : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QVariantList entries READ entries NOTIFY entriesChanged)

public:
    explicit MessageHistory(QObject *parent = nullptr);

    QVariantList entries() const { return m_entries; }

    // Called directly from CallEngine's C++ text-handling code (not
    // QML) whenever a message is sent, received, or changes status.
    void addSent(const QString &id, const QString &peer, const QString &message);
    void addReceived(const QString &id, const QString &peer, const QString &message);
    // status: "pending" | "sent" | "queued" | "delivered"
    void markStatus(const QString &id, const QString &status);

    // Wipes local history -- this device only; the peer's own copy of
    // what THEY sent/received is untouched (there is no shared history
    // to begin with). Useful before handing the phone to someone else.
    Q_INVOKABLE void clear();

signals:
    void entriesChanged();

private:
    QString filePath() const;
    void load();
    void save();
    void trimAndPublish();

    QVariantList m_entries;   // newest first; each entry a QVariantMap
};

#endif // MESSAGEHISTORY_H
