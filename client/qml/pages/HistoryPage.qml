import QtQuick 2.2
import Sailfish.Silica 1.0

Page {
    id: page

    // SilicaListView has no remorseAction() -- that helper lives on
    // ListItem, not on the views. Calling it threw a silent TypeError
    // and the clear never ran. RemorsePopup is the documented way to
    // get a remorse timer when the trigger is not a list item; it
    // reparents itself to the enclosing Page on execute() and draws
    // above the view, so its position in this file does not matter.
    RemorsePopup { id: clearRemorse }

    SilicaListView {
        id: listView
        anchors.fill: parent
        model: callEngine.history.entries

        header: Column {
            id: replyHeader
            width: listView.width

            // Ad-hoc reply. Deliberately here and not on the main page:
            // the main page stays a panic button plus one canned message,
            // and this is where you already are when you have just read
            // something and want to answer it.
            //
            // NOTE ON SCOPE: everything this needs lives inside this
            // Column. An id declared in a ListView header is NOT visible
            // from the enclosing Page -- a function on the Page calling
            // replyField.text throws "ReferenceError: replyField is not
            // defined" (verified against a real QQmlApplicationEngine;
            // qmllint does not resolve scopes and will not catch it).
            // The reverse direction is fine, which is why callEngine (a
            // context property) resolves here without qualification.
            function submit() {
                var text = replyField.text.trim()
                if (text === "")
                    return
                // No explicit success feedback needed: sendText() adds the
                // row to the local history straight away, so the message
                // shows up in the list below this field.
                callEngine.sendText(text)
                replyField.text = ""
                replyField.focus = false
            }

            PageHeader { title: qsTr("Message history") }

            TextField {
                id: replyField
                width: parent.width
                placeholderText: qsTr("Write a reply…")
                label: qsTr("Reply")
                // The engine trims and caps at 200 characters; stop typing
                // there rather than silently dropping the tail.
                maximumLength: 200
                EnterKey.enabled: text.trim() !== ""
                EnterKey.iconSource: "image://theme/icon-m-enter-accept"
                EnterKey.onClicked: replyHeader.submit()
            }

            Button {
                anchors.horizontalCenter: parent.horizontalCenter
                text: qsTr("Send")
                enabled: replyField.text.trim() !== ""
                onClicked: replyHeader.submit()
            }

            Item { width: 1; height: Theme.paddingLarge }
        }

        PullDownMenu {
            MenuItem {
                text: qsTr("Clear history")
                enabled: listView.count > 0
                // Sailfish's own idiom for a reversible destructive action:
                // a brief "undo" banner instead of a blocking confirm
                // dialog. Only actually clears once the remorse timeout
                // elapses without the user cancelling.
                onClicked: clearRemorse.execute(qsTr("Clearing history"),
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
