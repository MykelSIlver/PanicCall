#ifndef CONFIGSOURCE_H
#define CONFIGSOURCE_H

// Reads the same dconf keys the QML settings dialog writes
// (/apps/harbour-paniccall/*) via mlite, and follows live changes.
// Without mlite (dev builds outside SFOS) it falls back to environment
// variables: PANICCALL_URL, PANICCALL_TOKEN, PANICCALL_NAME,
// PANICCALL_AUTOANSWER=0|1.

#include <QObject>
#include <QString>

#ifdef HAVE_MLITE
#include <MGConfItem>
#endif

class ConfigSource : public QObject
{
    Q_OBJECT

public:
    explicit ConfigSource(QObject *parent = nullptr)
        : QObject(parent)
#ifdef HAVE_MLITE
        , m_url(QStringLiteral("/apps/harbour-paniccall/serverUrl"))
        , m_token(QStringLiteral("/apps/harbour-paniccall/token"))
        , m_name(QStringLiteral("/apps/harbour-paniccall/myName"))
        , m_auto(QStringLiteral("/apps/harbour-paniccall/autoAnswer"))
#endif
    {
#ifdef HAVE_MLITE
        connect(&m_url, &MGConfItem::valueChanged,
                this, &ConfigSource::changed);
        connect(&m_token, &MGConfItem::valueChanged,
                this, &ConfigSource::changed);
        connect(&m_name, &MGConfItem::valueChanged,
                this, &ConfigSource::changed);
        connect(&m_auto, &MGConfItem::valueChanged,
                this, &ConfigSource::changed);
#endif
    }

#ifdef HAVE_MLITE
    QString url() const { return m_url.value().toString(); }
    QString token() const { return m_token.value().toString(); }
    QString name() const { return m_name.value().toString(); }
    bool autoAnswer() const { return m_auto.value(true).toBool(); }
#else
    QString url() const
        { return QString::fromUtf8(qgetenv("PANICCALL_URL")); }
    QString token() const
        { return QString::fromUtf8(qgetenv("PANICCALL_TOKEN")); }
    QString name() const
        { return QString::fromUtf8(qgetenv("PANICCALL_NAME")); }
    bool autoAnswer() const
        { return qgetenv("PANICCALL_AUTOANSWER") != QByteArray("0"); }
#endif

signals:
    void changed();

private:
#ifdef HAVE_MLITE
    MGConfItem m_url;
    MGConfItem m_token;
    MGConfItem m_name;
    MGConfItem m_auto;
#endif
};

#endif // CONFIGSOURCE_H
