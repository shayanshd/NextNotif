package com.nextnotif.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentsTest {

    private fun pairing(transport: String? = null) = PairingInfo(
        code = "123456",
        role = Role.RECEIVER,
        server = "ws://relay.example.com",
        transport = transport,
    )

    @Test
    fun noEditingDefaultsToFcmOnDemand() {
        assertEquals(FcmOnDemand.TRANSPORT, initialTransportFor(null))
        assertEquals("fcm", initialTransportFor(null))
    }

    @Test
    fun editingWsPairingStaysWs() {
        assertEquals(TRANSPORT_WS, initialTransportFor(pairing(null)))
        assertEquals(TRANSPORT_WS, initialTransportFor(pairing(TRANSPORT_WS)))
    }

    @Test
    fun editingFirebasePairingStaysFirebase() {
        val fb = pairing(FirebaseRelay.TRANSPORT)
        assertEquals(FirebaseRelay.TRANSPORT, initialTransportFor(fb))
        assertEquals("firebase", initialTransportFor(fb))
    }

    @Test
    fun editingFcmPairingStaysFcm() {
        assertEquals(FcmOnDemand.TRANSPORT, initialTransportFor(pairing(FcmOnDemand.TRANSPORT)))
    }

    @Test
    fun unrecognizedTransportFallsBackToWs() {
        assertEquals(TRANSPORT_WS, initialTransportFor(pairing("carrier-pigeon")))
    }

    @Test
    fun partnerStatusSenderSeesReceiverSlotAndName() {
        val json = """{"exists":true,"sender_connected":true,"receiver_connected":true,
            "sender_name":"Samsung SM-A520F","receiver_name":"Xiaomi 23049PCD8G"}"""
        val (online, name) = partnerStatusFrom(json, Role.SENDER)
        assertEquals(true, online)
        assertEquals("Xiaomi 23049PCD8G", name)
        val (revOnline, revName) = partnerStatusFrom(json, Role.RECEIVER)
        assertEquals(true, revOnline)
        assertEquals("Samsung SM-A520F", revName)
    }

    @Test
    fun partnerStatusJsonNullNameIsUnknown() {
        val json = """{"exists":true,"sender_connected":false,"receiver_connected":true,"sender_name":null}"""
        val (senderOnline, senderName) = partnerStatusFrom(json, Role.SENDER)
        assertEquals(true, senderOnline) // partner = receiver slot, connected
        assertEquals(null, senderName) // no receiver_name key at all
        val (receiverOnline, receiverName) = partnerStatusFrom(json, Role.RECEIVER)
        assertEquals(false, receiverOnline) // partner = sender slot, offline
        assertEquals(null, receiverName) // sender_name is JSON null
    }

    @Test
    fun partnerStatusLiteralNullStringIsUnknown() {
        val json = """{"exists":true,"sender_connected":false,"receiver_connected":true,"sender_name":"null"}"""
        assertEquals(null, partnerStatusFrom(json, Role.SENDER).second)
        assertEquals(null, partnerStatusFrom(json, Role.RECEIVER).second)
    }

    @Test
    fun partnerStatusMissingNameKeyIsUnknown() {
        val json = """{"exists":true,"sender_connected":false,"receiver_connected":false}"""
        assertEquals(false, partnerStatusFrom(json, Role.SENDER).first)
        assertEquals(null, partnerStatusFrom(json, Role.SENDER).second)
        assertEquals(null, partnerStatusFrom(json, Role.RECEIVER).second)
    }

    @Test
    fun offlineSocketWithFcmTokenIsOnDemand() {
        val json = """{"receiver_connected":false,"receiver_has_fcm":true,"receiver_name":"Xiaomi"}"""
        val (state, name) = partnerStateFrom(json, Role.SENDER)
        assertEquals(AppState.ConnState.ON_DEMAND, state)
        assertEquals("Xiaomi", name)
    }

    @Test
    fun receiverFcmIsReadyAfterItsOwnRegistrationWithoutAnIdleSenderSocket() {
        assertEquals(
            PairingReadinessKind.READY,
            pairingReadinessKind(
                enabled = true,
                role = Role.RECEIVER,
                onDemand = true,
                ownState = AppState.ConnState.ON_DEMAND,
                partnerState = AppState.ConnState.DISCONNECTED,
                notificationPermissionMissing = false,
                error = null,
            ),
        )
    }

    @Test
    fun senderFcmWaitsUntilReceiverIsRegistered() {
        assertEquals(
            PairingReadinessKind.WAITING_FOR_PARTNER,
            pairingReadinessKind(
                enabled = true,
                role = Role.SENDER,
                onDemand = true,
                ownState = AppState.ConnState.CONNECTED,
                partnerState = AppState.ConnState.DISCONNECTED,
                notificationPermissionMissing = false,
                error = null,
            ),
        )
    }

    @Test
    fun receiverNotificationPermissionTakesPriorityOverReadyTransport() {
        assertEquals(
            PairingReadinessKind.NEEDS_NOTIFICATION_PERMISSION,
            pairingReadinessKind(
                enabled = true,
                role = Role.RECEIVER,
                onDemand = true,
                ownState = AppState.ConnState.ON_DEMAND,
                partnerState = AppState.ConnState.ON_DEMAND,
                notificationPermissionMissing = true,
                error = null,
            ),
        )
    }

    @Test
    fun rawTransportFailuresMapToActionableIssueCategories() {
        assertEquals(
            PairingIssueKind.ALERT_REGISTRATION,
            pairingIssueKind("FCM registration failed: timeout"),
        )
        assertEquals(
            PairingIssueKind.FIREBASE_CONFIG,
            pairingIssueKind("Firebase config is invalid (paste apiKey + databaseURL)"),
        )
        assertEquals(PairingIssueKind.NETWORK, pairingIssueKind("failed to connect to host"))
        assertEquals(PairingIssueKind.UNKNOWN, pairingIssueKind("unexpected close"))
    }
}
