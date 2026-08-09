#ifndef HISTORYPROXY_H
#define HISTORYPROXY_H

// D-Bus-backed mirror of MessageHistory's QML-facing surface (entries +
// clear()). Used by DaemonProxy so that HistoryPage.qml's
// `callEngine.history.entries` / `callEngine.history.clear()` keep
// working unmodified whether callEngine is a real CallEngine (local
// MessageHistory, file-backed) or a DaemonProxy (this class, fetching
// over D-Bus from the daemon's own CallEngine/MessageHistory).

#include <QObject>
#include <QVariantList>

class QDBusInterface;

class HistoryProxy : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QVariantList entries READ entries NOTIFY entriesChanged)

public:
    // Does not own iface -- borrowed from DaemonProxy, which owns the
    // single D-Bus connection to the daemon. iface must outlive this.
    explicit HistoryProxy(QDBusInterface *iface, QObject *parent = nullptr);

    QVariantList entries() const { return m_entries; }
    Q_INVOKABLE void clear();

public slots:
    // Re-fetches via the daemon's History() D-Bus method. Connected by
    // DaemonProxy to the daemon's HistoryChanged signal, so this stays
    // current without any manual polling.
    void refresh();

signals:
    void entriesChanged();

private:
    QDBusInterface *m_if;
    QVariantList m_entries;
};

#endif // HISTORYPROXY_H
