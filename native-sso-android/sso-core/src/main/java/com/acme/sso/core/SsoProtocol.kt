package com.acme.sso.core

/**
 * Messages of the Super App's bound SSO service. The service is protected by a signature-level
 * permission, so only apps signed with the vendor's certificate can bind to it; the service also
 * re-checks the caller's signature for every message.
 */
object SsoProtocol {
    const val SUPER_APP_PACKAGE = "com.acme.superapp"
    const val SERVICE_CLASS = "com.acme.superapp.SsoService"
    const val PERMISSION = "com.acme.permission.NATIVE_SSO"

    const val MSG_ENROLL = 1
    const val MSG_GET_BUNDLE = 2
    const val MSG_SIGN_DPOP = 3

    const val KEY_CLIENT_ID = "client_id"
    const val KEY_ID_TOKEN = "id_token"
    const val KEY_DEVICE_SECRET = "device_secret"
    const val KEY_HTM = "htm"
    const val KEY_HTU = "htu"
    const val KEY_ACCESS_TOKEN = "access_token"
    const val KEY_PROOF = "proof"
    const val KEY_JKT = "jkt"
    const val KEY_ERROR = "error"
    const val KEY_SUPER_CLIENT_ID = "super_client_id"
}
