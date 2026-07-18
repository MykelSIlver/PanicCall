import QtQuick 2.2
import Sailfish.Silica 1.0

CoverBackground {
    Label {
        anchors.centerIn: parent
        width: parent.width - 2 * Theme.paddingLarge
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.Wrap
        text: {
            switch (callEngine.state) {
            case "in_call": return qsTr("In call with %1").arg(callEngine.peerName)
            case "ringing": return qsTr("%1 is calling!").arg(callEngine.peerName)
            case "idle": return callEngine.peerOnline
                         ? qsTr("%1 is online").arg(callEngine.peerName)
                         : qsTr("%1 is offline").arg(callEngine.peerName)
            case "connecting": return qsTr("Connecting…")
            default: return qsTr("Not connected")
            }
        }
    }
    CoverActionList {
        enabled: callEngine.state === "in_call"
        CoverAction {
            iconSource: "image://theme/icon-cover-cancel"
            onTriggered: callEngine.hangup()
        }
    }
}
