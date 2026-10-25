package com.acme.sso.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Client side of the Super App's bound SSO service (see [SsoProtocol]). */
class SsoConnection private constructor(private val context: Context, private val conn: ServiceConnection,
                                        private val service: Messenger) {

    /** Sends one request and suspends until the Super App replies. */
    suspend fun request(what: Int, data: Bundle = Bundle()): Bundle = withTimeout(10_000) {
        suspendCancellableCoroutine { cont ->
            val replyTo = Messenger(Handler(Looper.getMainLooper()) { reply ->
                if (cont.isActive) cont.resume(reply.data ?: Bundle())
                true
            })
            service.send(Message.obtain(null, what).apply { this.data = data; this.replyTo = replyTo })
        }
    }

    fun close() = runCatching { context.unbindService(conn) }

    companion object {
        /**
         * Binds the Super App's SSO service. Fails with [SecurityException] when this app is not signed
         * with the vendor's certificate (the OS refuses the bind) and [IllegalStateException] when the
         * Super App is not installed.
         */
        suspend fun bind(context: Context): SsoConnection = withTimeout(10_000) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        if (cont.isActive) cont.resume(SsoConnection(context, this, Messenger(binder)))
                    }
                    override fun onServiceDisconnected(name: ComponentName) = Unit
                }
                val intent = Intent().setClassName(SsoProtocol.SUPER_APP_PACKAGE, SsoProtocol.SERVICE_CLASS)
                val bound = try {
                    context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                } catch (e: SecurityException) {
                    cont.resumeWithException(e); return@suspendCancellableCoroutine
                }
                if (!bound) cont.resumeWithException(IllegalStateException("the Acme Super App is not installed"))
            }
        }
    }
}
