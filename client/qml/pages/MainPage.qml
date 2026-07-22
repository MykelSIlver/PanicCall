import QtQuick 2.2
import Sailfish.Silica 1.0
import Nemo.Configuration 1.0

Page {
    id: page

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

    Component.onCompleted: {
        callEngine.autoAnswer = cfgAutoAnswer.value
        if (cfgToken.value !== "")
            callEngine.configure(cfgUrl.value, cfgToken.value, cfgName.value)
    }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height

        PullDownMenu {
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
            Rectangle {
                id: bigButton
                anchors.horizontalCenter: parent.horizontalCenter
                width: Math.min(page.width, page.height) * 0.6
                height: width
                radius: width / 2
                color: {
                    if (callEngine.state === "in_call")
                        return "#802020"
                    if (callEngine.state === "ringing")
                        return "#208020"
                    return callEngine.state === "idle" ? "#c02020" : "#404040"
                }
                opacity: mouseArea.pressed ? 0.7 : 1.0

                Label {
                    anchors.centerIn: parent
                    font.pixelSize: Theme.fontSizeExtraLarge
                    color: "white"
                    text: {
                        if (callEngine.state === "in_call") return qsTr("HANG UP")
                        if (callEngine.state === "ringing") return qsTr("ANSWER")
                        return qsTr("CALL %1").arg(callEngine.peerName !== ""
                                                  ? callEngine.peerName : "…")
                    }
                }

                MouseArea {
                    id: mouseArea
                    anchors.fill: parent
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
                callEngine.autoAnswer = autoSwitch.checked
                callEngine.configure(urlField.text, tokenField.text.trim(),
                                     nameField.text.trim())
            }
            Column {
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
            }
        }
    }
}
