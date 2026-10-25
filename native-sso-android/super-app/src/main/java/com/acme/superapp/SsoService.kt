package com.acme.superapp

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.acme.sso.core.SsoProtocol

/**
 * The Super App's Native SSO service. The manifest guards it with a signature-level permission;
 * every message is checked again against the caller's signing certificate, so a sibling app that
 * is not signed by the vendor can neither read the SSO bundle nor get key A to sign for it.
 */
class SsoService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("native-sso").apply { start() }
        messenger = Messenger(Handler(thread.looper) { msg -> handle(msg); true })
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        val reply = Bundle()
        val caller = packageManager.getNameForUid(msg.sendingUid) ?: "uid ${msg.sendingUid}"
        if (packageManager.checkSignatures(Process.myUid(), msg.sendingUid) != PackageManager.SIGNATURE_MATCH) {
            Log.w(TAG, "Refusing $caller: signed with a different certificate")
            reply.putString(SsoProtocol.KEY_ERROR, "caller is not signed by the vendor")
        } else {
            val session = SuperAppSession.get(this)
            try {
                when (msg.what) {
                    SsoProtocol.MSG_ENROLL -> {
                        val sibling = msg.data.getString(SsoProtocol.KEY_CLIENT_ID).orEmpty()
                        if (!session.enroll(sibling)) reply.putString(SsoProtocol.KEY_ERROR, "enrollment failed")
                        reply.putString(SsoProtocol.KEY_SUPER_CLIENT_ID, session.clientId)
                    }
                    SsoProtocol.MSG_GET_BUNDLE -> {
                        val idToken = session.idToken
                        val ds = session.deviceSecret
                        if (idToken == null || ds == null) {
                            reply.putString(SsoProtocol.KEY_ERROR, "not signed in to the Super App")
                        } else {
                            reply.putString(SsoProtocol.KEY_ID_TOKEN, idToken)
                            reply.putString(SsoProtocol.KEY_DEVICE_SECRET, ds)
                            reply.putString(SsoProtocol.KEY_SUPER_CLIENT_ID, session.clientId)
                        }
                    }
                    SsoProtocol.MSG_SIGN_DPOP -> {
                        val data = msg.data
                        reply.putString(SsoProtocol.KEY_PROOF, session.deviceKey.dpopProof(
                            data.getString(SsoProtocol.KEY_HTM).orEmpty(),
                            data.getString(SsoProtocol.KEY_HTU).orEmpty(),
                            data.getString(SsoProtocol.KEY_ACCESS_TOKEN),
                        ))
                        reply.putString(SsoProtocol.KEY_JKT, session.deviceKey.thumbprint)
                    }
                    else -> reply.putString(SsoProtocol.KEY_ERROR, "unknown request ${msg.what}")
                }
                Log.i(TAG, "Served request ${msg.what} for $caller")
            } catch (e: Exception) {
                reply.putString(SsoProtocol.KEY_ERROR, e.message ?: e.toString())
            }
        }
        msg.replyTo?.send(Message.obtain(null, msg.what).apply { data = reply })
    }

    companion object {
        private const val TAG = "NativeSso"
    }
}
