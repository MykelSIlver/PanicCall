import QtQuick 2.2
import Sailfish.Silica 1.0

Page {
    id: page

    SilicaListView {
        id: listView
        anchors.fill: parent
        model: callEngine.history.entries

        header: PageHeader { title: qsTr("Message history") }

        PullDownMenu {
            MenuItem {
                text: qsTr("Clear history")
                enabled: listView.count > 0
                // Sailfish's own idiom for a reversible destructive action:
                // a brief "undo" banner instead of a blocking confirm
                // dialog. Only actually clears once the remorse timeout
                // elapses without the user cancelling.
                onClicked: listView.remorseAction(qsTr("Clearing history"),
                    function() { callEngine.history.clear() })
            }
        }

        delegate: Column {
            width: listView.width
            spacing: Theme.paddingSmall / 2

            Item { width: 1; height: Theme.paddingMedium }

            Row {
                x: Theme.horizontalPageMargin
                spacing: Theme.paddingSmall
                Label {
                    text: modelData.direction === "sent" ? qsTr("You") : modelData.peer
                    font.pixelSize: Theme.fontSizeExtraSmall
                    font.bold: true
                    color: Theme.highlightColor
                }
                Label {
                    text: modelData.timestampText
                    font.pixelSize: Theme.fontSizeExtraSmall
                    color: Theme.secondaryColor
                }
                Label {
                    // Single checkmark, sent side only: blank = not yet
                    // confirmed delivered, check = the peer's client has
                    // processed it. No read-receipt distinction (v1 scope).
                    visible: modelData.direction === "sent"
                            && modelData.status === "delivered"
                    text: "\u2713"
                    color: "#4CAF50"
                    font.pixelSize: Theme.fontSizeExtraSmall
                    font.bold: true
                }
            }
            Label {
                x: Theme.horizontalPageMargin
                width: listView.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                text: modelData.message
                font.pixelSize: Theme.fontSizeSmall
            }
        }

        ViewPlaceholder {
            enabled: listView.count === 0
            text: qsTr("No messages yet.")
        }

        VerticalScrollDecorator {}
    }
}
