import QtQuick 2.2
import Sailfish.Silica 1.0
import Nemo.Configuration 1.0

Page {
    id: page

    // Transient feedback for our own sendText() tap: "sent" vs "queued".
    // Purely a UI concern -- no need for this to live in CallEngine.
    property string sendStatus: ""

    Connections {
        target: callEngine
        onTextSent: {
            sendStatus = queued
                ? qsTr("Message queued — will arrive when %1 comes online")
                      .arg(callEngine.peerName)
                : qsTr("Message sent")
            sendStatusTimer.restart()
        }
    }
    Timer {
        id: sendStatusTimer
        interval: 4000
        onTriggered: page.sendStatus = ""
    }

    ConfigurationValue {
        id: cfgUrl
        key: "/apps/harbour-paniccall/serverUrl"
        defaultValue: "wss://your-server.example/panic/ws"
    }
    ConfigurationValue {
        id: cfgToken
        key: "/apps/harbour-paniccall/token"
        defaultValue: ""
    }
    ConfigurationValue {
        id: cfgName
        key: "/apps/harbour-paniccall/myName"
        defaultValue: ""
    }
    ConfigurationValue {
        id: cfgAutoAnswer
        key: "/apps/harbour-paniccall/autoAnswer"
        defaultValue: true
    }
    ConfigurationValue {
        id: cfgNotifyPresence
        key: "/apps/harbour-paniccall/notifyPresence"
        defaultValue: false
    }
    ConfigurationValue {
        id: cfgQuickMessage
        key: "/apps/harbour-paniccall/quickMessage"
        defaultValue: qsTr("Call me on MeshChat instead")
    }

    Component.onCompleted: {
        // In daemon mode the daemon reads dconf itself and owns the
        // connection; touching it from here would disturb a live call.
        if (!daemonMode) {
            callEngine.autoAnswer = cfgAutoAnswer.value
            callEngine.notifyPresence = cfgNotifyPresence.value
            if (cfgToken.value !== "")
                callEngine.configure(cfgUrl.value, cfgToken.value, cfgName.value)
        }
    }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height

        PullDownMenu {
            MenuItem {
                text: qsTr("History")
                onClicked: pageStack.push(Qt.resolvedUrl("HistoryPage.qml"))
            }
            MenuItem {
                text: qsTr("Settings")
                onClicked: pageStack.push(settingsDialog)
            }
        }

        Column {
            id: column
            width: page.width
            spacing: Theme.paddingLarge

            PageHeader { title: "PanicCall" }

            Label {
                anchors.horizontalCenter: parent.horizontalCenter
                color: Theme.secondaryHighlightColor
                text: {
                    switch (callEngine.state) {
                    case "disconnected": return qsTr("Not connected")
                    case "connecting": return qsTr("Connecting…")
                    case "idle": return callEngine.peerOnline
                                 ? qsTr("%1 is online").arg(callEngine.peerName)
                                 : qsTr("%1 is offline").arg(callEngine.peerName)
                    case "ringing": return qsTr("%1 is calling!").arg(callEngine.peerName)
                    case "in_call": return qsTr("In call with %1").arg(callEngine.peerName)
                    }
                    return callEngine.state
                }
            }

            // The one big button. Red = call, green flash = ringing.
            // Greyed out and inert while idle-but-peer-offline: no point
            // letting the user tap into the "peer_offline" error path when
            // the UI already knows the call would fail.
            Rectangle {
                id: bigButton
                readonly property bool canCall: callEngine.state === "idle"
                                                && callEngine.peerOnline
                anchors.horizontalCenter: parent.horizontalCenter
                width: Math.min(page.width, page.height) * 0.6
                height: width
                radius: width / 2
                color: {
                    if (callEngine.state === "in_call")
                        return "#802020"
                    if (callEngine.state === "ringing")
                        return "#208020"
                    if (callEngine.state === "idle")
                        return canCall ? "#c02020" : "#404040"
                    return "#404040"
                }
                opacity: mouseArea.pressed ? 0.7 : 1.0

                Label {
                    anchors.centerIn: parent
                    width: parent.width - 2 * Theme.paddingMedium
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    font.pixelSize: Theme.fontSizeExtraLarge
                    color: "white"
                    text: {
                        if (callEngine.state === "in_call") return qsTr("HANG UP")
                        if (callEngine.state === "ringing") return qsTr("ANSWER")
                        if (callEngine.state === "idle" && !canCall)
                            return qsTr("%1 is offline").arg(callEngine.peerName !== ""
                                                             ? callEngine.peerName : "…")
                        return qsTr("CALL %1").arg(callEngine.peerName !== ""
                                                  ? callEngine.peerName : "…")
                    }
                }

                MouseArea {
                    id: mouseArea
                    anchors.fill: parent
                    // Disabled precisely for the idle-but-offline case; all
                    // other states (ringing/in_call, or idle-and-online)
                    // stay tappable exactly as before.
                    enabled: callEngine.state !== "idle" || callEngine.peerOnline
                    onClicked: {
                        if (callEngine.state === "in_call")
                            callEngine.hangup()
                        else if (callEngine.state === "ringing")
                            callEngine.answer()
                        else if (callEngine.state === "idle")
                            callEngine.startCall()
                    }
                }
            }

            Button {
                anchors.horizontalCenter: parent.horizontalCenter
                width: Math.min(implicitWidth, page.width - 4 * Theme.horizontalPageMargin)
                text: qsTr("Send: \"%1\"").arg(cfgQuickMessage.value)
                enabled: callEngine.state === "idle"
                onClicked: callEngine.sendText(cfgQuickMessage.value)
            }

            Label {
                anchors.horizontalCenter: parent.horizontalCenter
                width: page.width - 4 * Theme.horizontalPageMargin
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                font.pixelSize: Theme.fontSizeExtraSmall
                color: Theme.secondaryColor
                visible: page.sendStatus !== ""
                text: page.sendStatus
            }

            Label {
                anchors.horizontalCenter: parent.horizontalCenter
                width: page.width - 2 * Theme.horizontalPageMargin
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                visible: callEngine.lastError !== ""
                color: Theme.errorColor ? Theme.errorColor : "#ff4444"
                text: callEngine.lastError
            }
        }
    }

    Component {
        id: settingsDialog
        Dialog {
            onAccepted: {
                cfgUrl.value = urlField.text
                cfgToken.value = tokenField.text.trim()
                cfgName.value = nameField.text.trim()
                cfgAutoAnswer.value = autoSwitch.checked
                cfgNotifyPresence.value = presenceSwitch.checked
                cfgQuickMessage.value = quickMessageField.text.trim()
                if (!daemonMode) {
                    callEngine.autoAnswer = autoSwitch.checked
                    callEngine.notifyPresence = presenceSwitch.checked
                    callEngine.configure(urlField.text, tokenField.text.trim(),
                                         nameField.text.trim())
                }
                // daemon mode: the dconf writes above are enough; the
                // daemon follows them live via mlite
            }
            SilicaFlickable {
                anchors.fill: parent
                contentHeight: settingsColumn.height

                VerticalScrollDecorator {}

                Column {
                    id: settingsColumn
                    width: parent.width
                    DialogHeader { title: qsTr("Settings") }
                    TextField {
                        id: urlField
                        width: parent.width
                        label: qsTr("Relay URL")
                        text: cfgUrl.value
                        inputMethodHints: Qt.ImhNoAutoUppercase | Qt.ImhNoPredictiveText
                    }
                    TextField {
                        id: nameField
                        width: parent.width
                        label: qsTr("Your name")
                        description: qsTr("Shown on your contact's screen")
                        text: cfgName.value
                        placeholderText: qsTr("Your name")
                    }
                    TextField {
                        id: tokenField
                        width: parent.width
                        label: qsTr("Token (64 hex characters)")
                        text: cfgToken.value
                        inputMethodHints: Qt.ImhNoAutoUppercase | Qt.ImhNoPredictiveText
                    }
                    TextSwitch {
                        id: autoSwitch
                        text: qsTr("Auto-answer")
                        description: qsTr("Open audio immediately on an incoming call (baby monitor / emergency behaviour)")
                        checked: cfgAutoAnswer.value
                    }
                    TextSwitch {
                        id: presenceSwitch
                        text: qsTr("Presence chirp")
                        description: qsTr("Short sound when your contact comes online or goes offline")
                        checked: cfgNotifyPresence.value
                    }
                    TextField {
                        id: quickMessageField
                        width: parent.width
                        label: qsTr("Quick message")
                        description: qsTr("One-tap message the button on the main screen sends")
                        text: cfgQuickMessage.value
                        maximumLength: 200
                    }
                }
            }
        }
    }
}
