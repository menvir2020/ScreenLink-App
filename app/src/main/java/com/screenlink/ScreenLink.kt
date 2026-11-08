// ScreenLink para Android (celular, tablet y Android TV / JVC) - ARCHIVO ÚNICO
// Modos: Transmisor (comparte su pantalla) y Receptor (ve y controla otro dispositivo).
// Protocolo: ver PROTOCOL.md (v2). Misma red Wi-Fi, sin servidores externos.
package com.screenlink

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val TAG = "ScreenLink"

// =====================================================================
//  Modelos
// =====================================================================

enum class Role { SHARER, VIEWER }

data class Device(val id: String, val name: String, val host: String, val port: Int, val platform: String)

class Session(
    val peer: WebSocket,
    val peerName: String,
    val role: Role,
    var control: Boolean,
    val initiator: Boolean,
) {
    var active = false
    var projection: Intent? = null
    var timeout: Runnable? = null
}

class Incoming(val peerName: String, val wantsControl: Boolean)

/** La pantalla (MainActivity) implementa esto para recibir avisos del núcleo. */
interface UiListener {
    fun onDevices()
    fun onIncoming(req: Incoming, decide: (Boolean, Boolean) -> Unit)
    fun onNeedProjection(result: (Intent?) -> Unit)
    fun onSessionStart(s: Session)
    fun onSessionEnd(reason: String)
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }
}

// =====================================================================
//  Núcleo: configuración, servidor WebSocket, señalización y permisos
// =====================================================================

object Core {
    const val VERSION = 2
    private const val PREFERRED_PORT = 48200

    lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    val main = Handler(Looper.getMainLooper())

    var deviceId = ""
    var deviceName = ""
    var mode = "tx" // "tx" = Transmisor, "rx" = Receptor
    var isTv = false
    var port = 0
    val platform: String get() = if (isTv) "androidtv" else "android"

    val devices = LinkedHashMap<String, Device>()
    var session: Session? = null
    var ui: UiListener? = null

    private var server: Server? = null
    lateinit var discovery: Discovery

    fun init(application: Application) {
        app = application
        prefs = app.getSharedPreferences("screenlink", Context.MODE_PRIVATE)
        deviceId = prefs.getString("id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
        val uiMode = app.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        isTv = uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            app.packageManager.hasSystemFeature("android.software.leanback")
        deviceName = prefs.getString("name", null) ?: defaultName()
        mode = prefs.getString("mode", "tx") ?: "tx"

        port = pickPort()
        server = Server(port).also {
            it.isReuseAddr = true
            it.start()
        }
        discovery = Discovery(app)
        discovery.start()
    }

    private fun defaultName(): String {
        val model = (Build.MODEL ?: "Android").take(30)
        return if (isTv) "$model TV" else model
    }

    private fun pickPort(): Int = try {
        ServerSocket(PREFERRED_PORT).use { it.localPort }
    } catch (_: Exception) {
        ServerSocket(0).use { it.localPort }
    }

    fun rename(newName: String) {
        val n = newName.trim().take(40)
        if (n.isEmpty()) return
        deviceName = n
        prefs.edit().putString("name", n).apply()
        discovery.applyPublish()
    }

    fun changeMode(m: String) {
        mode = m
        prefs.edit().putString("mode", m).apply()
        discovery.applyPublish()
        if (m == "rx") discovery.startSearch() else discovery.stopSearch()
    }

    // ---------- Dispositivos descubiertos ----------

    fun clearDevices() {
        main.post {
            devices.clear()
            ui?.onDevices()
        }
    }

    fun addDevice(d: Device) {
        main.post {
            devices[d.id] = d
            ui?.onDevices()
        }
    }

    fun removeDevice(id: String) {
        main.post { if (devices.remove(id) != null) ui?.onDevices() }
    }

    // ---------- Dispositivos de confianza ----------

    private fun trustMap(): JSONObject = try {
        JSONObject(prefs.getString("trusted", "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun saveTrust(id: String, name: String, control: Boolean) {
        val m = trustMap()
        m.put(id, JSONObject().put("name", name).put("control", control))
        prefs.edit().putString("trusted", m.toString()).apply()
    }

    fun clearTrusted() {
        prefs.edit().remove("trusted").apply()
    }

    // ---------- Red ----------

    private fun isLan(a: InetAddress?): Boolean =
        a != null && (a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress)

    fun sendJson(ws: WebSocket, obj: JSONObject) {
        try {
            ws.send(obj.toString())
        } catch (_: Exception) {
        }
    }

    fun sendSignal(data: JSONObject) {
        val s = session ?: return
        sendJson(s.peer, JSONObject().put("t", "signal").put("data", data))
    }

    private class Server(p: Int) : WebSocketServer(InetSocketAddress(p)) {
        override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
            val c = conn ?: return
            if (!Core.isLan(c.remoteSocketAddress?.address)) {
                c.close()
                return
            }
            // Si no manda "hello" en 5 s, se descarta.
            Core.main.postDelayed({
                if (c.getAttachment<Boolean>() != true) c.close()
            }, 5000)
        }

        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
            val c = conn ?: return
            Core.main.post { Core.onPeerClosed(c) }
        }

        override fun onMessage(conn: WebSocket?, message: String?) {
            val c = conn ?: return
            val m = message ?: return
            Core.main.post { Core.route(c, m) }
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {
            Log.w(TAG, "Error del servidor WebSocket", ex)
        }

        override fun onStart() {
            Log.i(TAG, "Servidor WebSocket escuchando")
        }
    }

    // ---------- Enrutado de mensajes ----------

    internal fun route(ws: WebSocket, text: String) {
        val msg = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        val t = msg.optString("t")
        val s = session

        if (s == null || s.peer !== ws) {
            if (t == "hello" && ws !is WebSocketClient && ws.getAttachment<Boolean>() != true) {
                ws.setAttachment(true)
                handleHello(ws, msg)
            }
            return
        }

        when (t) {
            "accept" -> if (s.initiator && !s.active) {
                s.control = msg.optBoolean("control") // el transmisor decide si concede el control
                activate(s)
            }
            "reject" -> endSession(
                when (msg.optString("reason")) {
                    "busy" -> "El transmisor está ocupado con otra sesión."
                    "notx" -> "Ese dispositivo no está en modo Transmisor."
                    else -> "El transmisor rechazó la conexión."
                },
                false
            )
            "signal" -> if (s.active) msg.optJSONObject("data")?.let { Rtc.onSignal(it) }
            "bye" -> endSession("El otro dispositivo terminó la sesión.", false)
        }
    }

    internal fun onPeerClosed(ws: WebSocket) {
        val s = session
        if (s != null && s.peer === ws) endSession("Se perdió la conexión.", false)
    }

    private fun onPeerError(ws: WebSocket) {
        val s = session
        if (s != null && s.peer === ws && !s.active) endSession("No se pudo conectar.", false)
    }

    // ---------- Solicitud entrante (somos Transmisor) ----------

    private fun handleHello(ws: WebSocket, msg: JSONObject) {
        val id = msg.optString("id")
        val name = msg.optString("name").take(40)
        val valid = msg.optInt("v") == VERSION && id.isNotEmpty() && id.length <= 64
        if (!valid) {
            ws.close()
            return
        }
        if (mode != "tx") {
            sendJson(ws, JSONObject().put("t", "reject").put("reason", "notx"))
            ws.close()
            return
        }
        if (session != null) {
            sendJson(ws, JSONObject().put("t", "reject").put("reason", "busy"))
            ws.close()
            return
        }
        val s = Session(ws, name, Role.SHARER, control = false, initiator = false)
        session = s
        decide(s, id, name, msg.optBoolean("control"))
    }

    private fun decide(s: Session, id: String, name: String, wants: Boolean) {
        val trust = trustMap().optJSONObject(id)
        val trustedControl = trust?.optBoolean("control") ?: false

        // Dispositivo de confianza: conexión inmediata, sin preguntar.
        if (trust != null && (!wants || trustedControl)) {
            needProjection(s, wants && trustedControl)
            return
        }
        val u = ui
        if (u == null) {
            rejectAndClear(s, "denied")
            return
        }
        u.onIncoming(Incoming(name, wants)) { allow, always ->
            main.post {
                if (session !== s) return@post
                if (!allow) {
                    rejectAndClear(s, "denied")
                    return@post
                }
                if (always) saveTrust(id, name, wants)
                needProjection(s, wants)
            }
        }
    }

    // Para emitir la pantalla Android exige un permiso del usuario (captura de pantalla).
    private fun needProjection(s: Session, granted: Boolean) {
        val u = ui
        if (u == null) {
            rejectAndClear(s, "denied")
            return
        }
        u.onNeedProjection { data ->
            main.post {
                if (session !== s) return@post
                if (data == null) {
                    rejectAndClear(s, "denied")
                } else {
                    s.projection = data
                    s.control = granted
                    sendJson(s.peer, JSONObject().put("t", "accept").put("control", granted))
                    activate(s)
                }
            }
        }
    }

    private fun rejectAndClear(s: Session, reason: String) {
        sendJson(s.peer, JSONObject().put("t", "reject").put("reason", reason))
        if (session === s) session = null
        try {
            s.peer.close()
        } catch (_: Exception) {
        }
    }

    // ---------- Conexión saliente (somos Receptor) ----------

    /** Devuelve un mensaje de error, o null si la conexión se inició. */
    fun connect(host: String, port: Int, control: Boolean, peerName: String): String? {
        if (session != null) return "Ya hay una sesión activa."
        val uri = try {
            URI("ws://$host:$port")
        } catch (_: Exception) {
            return "Dirección inválida."
        }
        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                send(
                    JSONObject().put("t", "hello").put("v", VERSION).put("id", deviceId)
                        .put("name", deviceName).put("platform", platform).put("control", control).toString()
                )
            }

            override fun onMessage(message: String?) {
                val m = message ?: return
                main.post { route(this, m) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                main.post { onPeerClosed(this) }
            }

            override fun onError(ex: Exception?) {
                main.post { onPeerError(this) }
            }
        }
        val s = Session(client, peerName, Role.VIEWER, control = false, initiator = true)
        val timeout = Runnable { endSession("No hubo respuesta del transmisor.") }
        s.timeout = timeout
        main.postDelayed(timeout, 60000)
        session = s
        client.connect()
        return null
    }

    // ---------- Ciclo de vida de la sesión ----------

    private fun activate(s: Session) {
        s.active = true
        s.timeout?.let { main.removeCallbacks(it) }
        Rtc.onSessionStart(s)
        ui?.onSessionStart(s)
    }

    fun endSession(reason: String, notifyPeer: Boolean = true) {
        val s = session ?: return
        session = null
        s.timeout?.let { main.removeCallbacks(it) }
        if (notifyPeer) sendJson(s.peer, JSONObject().put("t", "bye"))
        try {
            s.peer.close()
        } catch (_: Exception) {
        }
        Rtc.stop()
        ui?.onSessionEnd(reason)
    }

    /** Evento de control del receptor. Solo se aplica si este equipo concedió el control. */
    fun onControl(ev: JSONObject) {
        val s = session ?: return
        if (s.active && s.role == Role.SHARER && s.control) ControlService.instance?.handle(ev)
    }
}

// =====================================================================
//  Descubrimiento (mDNS / NSD): _screenlink._tcp
// =====================================================================

class Discovery(private val ctx: Context) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var republish = false
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val names = HashMap<String, String>() // nombre de servicio -> id del dispositivo
    private var resolving = false

    fun start() {
        // Sin el bloqueo de multidifusión, muchos equipos no reciben los anuncios mDNS.
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("screenlink").apply {
            setReferenceCounted(false)
            acquire()
        }
        applyPublish()
        if (Core.mode == "rx") startSearch()
    }

    // ---------- Publicación: solo somos visibles en modo Transmisor ----------

    fun applyPublish() {
        Core.main.post {
            val l = regListener
            if (l == null) {
                if (Core.mode == "tx") register()
                return@post
            }
            republish = true
            try {
                nsd.unregisterService(l)
            } catch (_: Exception) {
                regListener = null
                republish = false
                if (Core.mode == "tx") register()
            }
        }
    }

    private fun register() {
        val info = NsdServiceInfo().apply {
            serviceName = Core.deviceName
            serviceType = "_screenlink._tcp"
            port = Core.port
            setAttribute("id", Core.deviceId)
            setAttribute("name", Core.deviceName)
            setAttribute("v", Core.VERSION.toString())
            setAttribute("platform", Core.platform)
            setAttribute("role", "tx")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Core.main.post { regListener = null }
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                Core.main.post {
                    regListener = null
                    if (republish) {
                        republish = false
                        if (Core.mode == "tx") register()
                    }
                }
            }
        }
        regListener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    // ---------- Búsqueda: solo en modo Receptor ----------

    fun startSearch() {
        Core.main.post {
            stopSearchNow()
            Core.clearDevices()
            queue.clear()
            names.clear()
            resolving = false
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    val info = serviceInfo ?: return
                    if (info.serviceType?.contains("screenlink") != true) return
                    Core.main.post {
                        queue.addLast(info)
                        resolveNext()
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                    val name = serviceInfo?.serviceName ?: return
                    Core.main.post { names.remove(name)?.let { Core.removeDevice(it) } }
                }
            }
            discListener = l
            try {
                nsd.discoverServices("_screenlink._tcp", NsdManager.PROTOCOL_DNS_SD, l)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo iniciar la búsqueda", e)
            }
        }
    }

    fun stopSearch() {
        Core.main.post { stopSearchNow() }
    }

    private fun stopSearchNow() {
        discListener?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        discListener = null
    }

    // NsdManager solo permite resolver un servicio a la vez, así que se encolan.
    private fun resolveNext() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Core.main.post {
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                Core.main.post {
                    resolving = false
                    serviceInfo?.let { handleResolved(it) }
                    resolveNext()
                }
            }
        })
    }

    private fun handleResolved(i: NsdServiceInfo) {
        val attrs = i.attributes
        fun attr(k: String): String? = attrs[k]?.let { String(it, Charsets.UTF_8) }

        if (attr("role") != "tx") return
        val id = attr("id") ?: return
        if (id == Core.deviceId) return
        val host = (i.host as? Inet4Address)?.hostAddress ?: i.host?.hostAddress ?: return
        names[i.serviceName] = id
        Core.addDevice(
            Device(id, (attr("name") ?: i.serviceName).take(40), host, i.port, attr("platform") ?: "")
        )
    }
}

// =====================================================================
//  Vídeo remoto: dibuja el fotograma ESTIRADO a toda la pantalla (sin franjas negras)
//  o, si se prefiere, respetando la proporción.
// =====================================================================

class FillRenderer(ctx: Context) : SurfaceView(ctx), SurfaceHolder.Callback, VideoSink {
    private val egl = EglRenderer("screenlink-view")

    @Volatile
    var frameW = 0

    @Volatile
    var frameH = 0

    var stretch = true
        set(v) {
            field = v
            requestLayout()
        }

    init {
        holder.addCallback(this)
        egl.init(Rtc.eglBase().eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
    }

    override fun onFrame(frame: VideoFrame) {
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w != frameW || h != frameH) {
            frameW = w
            frameH = h
            egl.setLayoutAspectRatio(w.toFloat() / h) // se dibuja el fotograma completo, sin recortes
            post { requestLayout() }
        }
        egl.onFrame(frame)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val pw = View.MeasureSpec.getSize(widthSpec)
        val ph = View.MeasureSpec.getSize(heightSpec)
        if (stretch || frameW == 0 || frameH == 0) {
            setMeasuredDimension(pw, ph)
        } else {
            val s = min(pw.toFloat() / frameW, ph.toFloat() / frameH)
            setMeasuredDimension((frameW * s).toInt(), (frameH * s).toInt())
        }
    }

    fun clear() {
        egl.clearImage()
    }

    fun release() {
        egl.release()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        egl.createEglSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val latch = CountDownLatch(1)
        egl.releaseEglSurface { latch.countDown() }
        latch.await(500, TimeUnit.MILLISECONDS)
    }
}

// =====================================================================
//  WebRTC: el transmisor crea la oferta; el receptor responde
// =====================================================================

object Rtc {
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var localTrack: VideoTrack? = null
    private var sink: VideoSink? = null
    private val pending = ArrayList<IceCandidate>()
    private var remoteSet = false

    var remoteTrack: VideoTrack? = null
        private set
    var onVideo: (() -> Unit)? = null

    fun eglBase(): EglBase {
        egl?.let { return it }
        return EglBase.create().also { egl = it }
    }

    private fun factory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(Core.app).createInitializationOptions()
        )
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase().eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase().eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    // Solo red local: sin servidores STUN/TURN.
    private fun config() = PeerConnection.RTCConfiguration(emptyList()).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    fun onSessionStart(s: Session) {
        stop()
        if (s.role == Role.VIEWER) {
            pending.clear()
            remoteSet = false
            pc = factory().createPeerConnection(config(), observer)
            if (pc == null) Core.endSession("No se pudo iniciar WebRTC.")
        } else {
            // El servicio en primer plano llama a startSharer() cuando ya está activo (obligatorio en Android 14+).
            SessionService.start(Core.app)
        }
    }

    // ---------- Transmisor ----------

    fun startSharer() {
        val s = Core.session ?: return
        val data: Intent = s.projection ?: return
        if (!s.active || s.role != Role.SHARER) return
        try {
            pending.clear()
            remoteSet = false
            val f = factory()
            val conn = f.createPeerConnection(config(), observer)
                ?: throw IllegalStateException("No se pudo iniciar WebRTC.")
            pc = conn

            val h = SurfaceTextureHelper.create("ScreenCapture", eglBase().eglBaseContext)
            helper = h
            val src = f.createVideoSource(true)
            source = src
            val cap = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() {
                    Core.main.post { Core.endSession("Se detuvo la captura de pantalla.") }
                }
            })
            capturer = cap
            cap.initialize(h, Core.app, src.capturerObserver)
            val (w, hh) = captureSize()
            cap.startCapture(w, hh, 30)

            val track = f.createVideoTrack("screen", src)
            localTrack = track
            val sender = conn.addTrack(track, listOf("screenlink"))
            try {
                val p = sender.parameters
                p.encodings.forEach { it.maxBitrateBps = 6_000_000 }
                sender.setParameters(p)
            } catch (_: Exception) {
            }

            val ch = conn.createDataChannel("control", DataChannel.Init())
            dc = ch
            ch.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {}
                override fun onMessage(buffer: DataChannel.Buffer?) {
                    val b = buffer ?: return
                    if (b.binary || b.data.remaining() > 512) return
                    val bytes = ByteArray(b.data.remaining())
                    b.data.get(bytes)
                    val ev = try {
                        JSONObject(String(bytes, Charsets.UTF_8))
                    } catch (_: Exception) {
                        return
                    }
                    Core.main.post { Core.onControl(ev) }
                }
            })

            conn.createOffer(object : Sdp() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    val d = desc ?: return
                    conn.setLocalDescription(Sdp(), d)
                    sendSdp(d)
                }
            }, MediaConstraints())
        } catch (e: Exception) {
            Core.main.post { Core.endSession("No se pudo compartir la pantalla: ${e.message}") }
        }
    }

    // Se captura a la proporción real de la pantalla, hasta 1280 px en el lado largo.
    private fun captureSize(): Pair<Int, Int> {
        val dm = Core.app.resources.displayMetrics
        val longSide = max(dm.widthPixels, dm.heightPixels)
        val scale = min(1f, 1280f / longSide)
        val w = (dm.widthPixels * scale).toInt() and 1.inv()
        val h = (dm.heightPixels * scale).toInt() and 1.inv()
        return Pair(max(w, 2), max(h, 2))
    }

    // ---------- Señalización ----------

    private fun sendSdp(d: SessionDescription) {
        val data = JSONObject().put("sdp", JSONObject().put("type", d.type.canonicalForm()).put("sdp", d.description))
        // Los callbacks de WebRTC llegan en otro hilo; el estado del núcleo solo se toca en el principal.
        Core.main.post { Core.sendSignal(data) }
    }

    fun onSignal(data: JSONObject) {
        val conn = pc ?: return
        val sdp = data.optJSONObject("sdp")
        if (sdp != null) {
            val type = SessionDescription.Type.fromCanonicalForm(sdp.optString("type"))
            val desc = SessionDescription(type, sdp.optString("sdp"))
            conn.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() {
                    Core.main.post {
                        remoteSet = true
                        pending.forEach { conn.addIceCandidate(it) }
                        pending.clear()
                        if (type == SessionDescription.Type.OFFER) answer(conn)
                    }
                }
            }, desc)
            return
        }
        val c = data.optJSONObject("candidate") ?: return
        val cand = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))
        if (remoteSet) conn.addIceCandidate(cand) else pending.add(cand)
    }

    private fun answer(conn: PeerConnection) {
        conn.createAnswer(object : Sdp() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                val d = desc ?: return
                conn.setLocalDescription(Sdp(), d)
                sendSdp(d)
            }
        }, MediaConstraints())
    }

    // ---------- Control (receptor -> transmisor) ----------

    fun sendControl(ev: JSONObject) {
        val ch = dc ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(ByteBuffer.wrap(ev.toString().toByteArray(Charsets.UTF_8)), false))
    }

    // ---------- Vídeo remoto ----------

    fun attachSink(s: VideoSink?) {
        sink?.let { old -> remoteTrack?.let { runCatching { it.removeSink(old) } } }
        sink = s
        if (s != null) remoteTrack?.addSink(s)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onRenegotiationNeeded() {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            if (state == PeerConnection.IceConnectionState.FAILED) {
                Core.main.post { Core.endSession("Falló la conexión de vídeo. Revisa que ambos estén en el mismo Wi-Fi.") }
            }
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            val c = candidate ?: return
            val data = JSONObject().put(
                "candidate",
                JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
            )
            Core.main.post { Core.sendSignal(data) }
        }

        override fun onDataChannel(channel: DataChannel?) {
            dc = channel // lado receptor: el transmisor crea el canal "control"
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track()
            if (track is VideoTrack) {
                Core.main.post {
                    remoteTrack = track
                    sink?.let { track.addSink(it) }
                    onVideo?.invoke()
                }
            }
            // El audio remoto (si lo hay) se reproduce solo, por la salida activa (altavoz o Bluetooth).
        }
    }

    // ---------- Cierre ----------

    fun stop() {
        try {
            dc?.close()
            dc?.dispose()
        } catch (_: Exception) {
        }
        dc = null
        try {
            capturer?.stopCapture()
        } catch (_: Exception) {
        }
        capturer?.dispose()
        capturer = null
        helper?.dispose()
        helper = null
        localTrack?.dispose()
        localTrack = null
        source?.dispose()
        source = null
        sink?.let { s -> remoteTrack?.let { runCatching { it.removeSink(s) } } }
        remoteTrack = null
        try {
            pc?.close()
            pc?.dispose()
        } catch (_: Exception) {
        }
        pc = null
        pending.clear()
        remoteSet = false
        SessionService.stop(Core.app)
    }

    /** Observador de SDP con métodos vacíos por defecto. */
    open class Sdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}

// =====================================================================
//  Servicio en primer plano (obligatorio para capturar la pantalla)
// =====================================================================

class SessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Core.main.post { Core.endSession("Sesión terminada.") }
            return START_NOT_STICKY
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Compartiendo pantalla", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ScreenLink")
            .setContentText("Estás compartiendo tu pantalla")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        Core.main.post { Rtc.startSharer() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "screenlink_share"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.screenlink.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SessionService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SessionService::class.java))
        }
    }
}

// =====================================================================
//  Servicio de accesibilidad: permite que el receptor controle este dispositivo
//  (clic/arrastre -> toque o deslizamiento; clic derecho -> Atrás; clic central -> Inicio;
//   rueda -> desplazamiento; teclas -> texto, Atrás, Inicio y flechas/OK de mando en Android 13+)
// =====================================================================

class ControlService : AccessibilityService() {
    private val points = ArrayList<FloatArray>()
    private var dragging = false
    private var downTime = 0L
    private var downButton = 0
    private var lastX = -1f
    private var lastY = -1f
    private var shift = false

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    fun handle(ev: JSONObject) {
        when (ev.optString("k")) {
            "move" -> {
                moveTo(ev)
                if (dragging) addPoint(lastX, lastY)
            }
            "down" -> {
                moveTo(ev)
                dragging = true
                points.clear()
                points.add(floatArrayOf(lastX, lastY))
                downTime = SystemClock.uptimeMillis()
                downButton = ev.optInt("b")
            }
            "up" -> up(ev)
            "wheel" -> wheel(ev)
            "key" -> key(ev.optInt("vk"), ev.optBoolean("d"))
            "text" -> typeText(ev.optString("s").take(64))
        }
    }

    @Suppress("DEPRECATION")
    private fun screen(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun clamp01(v: Double): Float = if (v.isNaN()) 0f else min(1.0, max(0.0, v)).toFloat()

    private fun moveTo(ev: JSONObject) {
        val (w, h) = screen()
        lastX = clamp01(ev.optDouble("x")) * (w - 1)
        lastY = clamp01(ev.optDouble("y")) * (h - 1)
    }

    private fun addPoint(x: Float, y: Float) {
        val last = points.lastOrNull()
        if (points.size < 300 && (last == null || hypot(x - last[0], y - last[1]) > 4f)) points.add(floatArrayOf(x, y))
    }

    private fun up(ev: JSONObject) {
        if (!dragging) return
        dragging = false
        moveTo(ev)
        addPoint(lastX, lastY)

        if (downButton == 2) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        if (downButton == 1) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        val first = points.firstOrNull() ?: return
        val last = points.last()
        val elapsed = SystemClock.uptimeMillis() - downTime
        val moved = hypot(last[0] - first[0], last[1] - first[1]) > 24f

        val path = Path()
        path.moveTo(first[0], first[1])
        val duration: Long
        if (moved) {
            for (i in 1 until points.size) path.lineTo(points[i][0], points[i][1])
            duration = elapsed.coerceIn(80L, 3000L)
        } else {
            duration = if (elapsed > 500) min(elapsed, 2000L) else 50L // pulsación larga o toque
        }
        gesture(path, duration)
    }

    private fun wheel(ev: JSONObject) {
        val dy = ev.optDouble("dy", 0.0)
        if (dy.isNaN() || dy == 0.0) return
        val (w, h) = screen()
        val x = if (lastX >= 0) lastX else w / 2f
        val y = if (lastY >= 0) lastY else h / 2f
        // En la web, dy > 0 = bajar: el dedo debe subir para bajar el contenido.
        val dist = min(h * 0.5, max(80.0, abs(dy) * 2.5)).toFloat()
        val toY = (y - Math.signum(dy).toFloat() * dist).coerceIn(1f, h - 1f)
        val path = Path()
        path.moveTo(x, y)
        path.lineTo(x, toY)
        gesture(path, 150L)
    }

    private fun gesture(path: Path, duration: Long) {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        dispatchGesture(g, null, null)
    }

    // vk = códigos de tecla virtuales de Windows (los mismos que envía la app de PC).
    private fun key(vk: Int, down: Boolean) {
        if (vk == 16) {
            shift = down
            return
        }
        if (!down) return
        when {
            vk in 65..90 -> {
                val c = vk.toChar()
                typeText(if (shift) c.toString() else c.lowercaseChar().toString())
            }
            vk in 48..57 -> typeText(vk.toChar().toString())
            vk == 32 -> typeText(" ")
            vk == 8 -> if (focusedInput() != null) backspace() else performGlobalAction(GLOBAL_ACTION_BACK)
            vk == 13 -> enter()
            vk == 27 -> performGlobalAction(GLOBAL_ACTION_BACK)
            vk == 36 || vk == 91 || vk == 92 -> performGlobalAction(GLOBAL_ACTION_HOME)
            vk in 37..40 -> dpad(vk)
        }
    }

    // Flechas y OK de mando: solo Android 13 (API 33) o superior las permite desde accesibilidad.
    private fun dpad(vk: Int) {
        if (Build.VERSION.SDK_INT < 33) return
        when (vk) {
            37 -> performGlobalAction(GLOBAL_ACTION_DPAD_LEFT)
            38 -> performGlobalAction(GLOBAL_ACTION_DPAD_UP)
            39 -> performGlobalAction(GLOBAL_ACTION_DPAD_RIGHT)
            40 -> performGlobalAction(GLOBAL_ACTION_DPAD_DOWN)
        }
    }

    private fun focusedInput(): AccessibilityNodeInfo? = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun currentText(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString() ?: ""

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun typeText(s: String) {
        if (s.isEmpty()) return
        val node = focusedInput() ?: return
        setText(node, currentText(node) + s)
    }

    private fun backspace() {
        val node = focusedInput() ?: return
        setText(node, currentText(node).dropLast(1))
    }

    private fun enter() {
        val node = focusedInput()
        if (node == null) {
            if (Build.VERSION.SDK_INT >= 33) performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else {
            setText(node, currentText(node) + "\n")
        }
    }

    companion object {
        @Volatile
        var instance: ControlService? = null

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return s.contains(ctx.packageName)
        }
    }
}

// =====================================================================
//  Pantalla única (celular vertical; tablet y TV horizontal), construida por código
// =====================================================================

class MainActivity : Activity(), UiListener {
    private val BG = Color.parseColor("#272C48")
    private val TXT = Color.parseColor("#F1F1F6")
    private val YELLOW = Color.parseColor("#FFC000")
    private val BLUE = Color.parseColor("#5BC8F5")
    private val EDGE = Color.parseColor("#0C2A6B")
    private val MODE = Color.parseColor("#1F6FC0")
    private val DANGER = Color.parseColor("#E5484D")

    private val sc get() = if (Core.isTv) 1.35f else 1f
    private val wide get() = Core.isTv || resources.configuration.smallestScreenWidthDp >= 600

    private lateinit var root: FrameLayout
    private lateinit var homeView: ScrollView
    private lateinit var sessionView: FrameLayout
    private var inSession = false

    // Inicio
    private lateinit var nameEdit: EditText
    private lateinit var btnTx: Button
    private lateinit var btnRx: Button
    private lateinit var rxBox: LinearLayout
    private lateinit var txBox: LinearLayout
    private lateinit var accStatus: TextView
    private lateinit var controlCheck: CheckBox
    private lateinit var listBox: LinearLayout
    private lateinit var hint: TextView
    private lateinit var manualRow: LinearLayout
    private lateinit var ipEdit: EditText
    private var searching = false

    // Sesión
    private lateinit var renderer: FillRenderer
    private lateinit var bar: LinearLayout
    private lateinit var infoText: TextView
    private lateinit var waitingText: TextView
    private lateinit var sharingText: TextView
    private lateinit var cursor: View
    private lateinit var fitBtn: Button
    private lateinit var rightBtn: Button
    private lateinit var keyboardBtn: Button
    private lateinit var hiddenInput: EditText
    private var barShown = false
    private var rightMode = false
    private var kbShown = false

    // Control táctil
    private var dragging = false
    private var scrolling = false
    private var lastScrollY = 0f
    private var lastMoveAt = 0L
    private var touchButton = 0

    // Cursor virtual (mando de TV)
    private var cx = 0.5f
    private var cy = 0.5f

    private var projectionCallback: ((Intent?) -> Unit)? = null

    // ---------- Ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        homeView = buildHome()
        sessionView = buildSession()
        root.addView(homeView, FrameLayout.LayoutParams(-1, -1))
        root.addView(sessionView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 && !Core.isTv &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Core.ui = this
        Rtc.attachSink(renderer)
        Rtc.onVideo = { updateVideoState() }
        refreshMode()
        refreshAccessibility()
        refreshDevices()
        if (Core.mode == "rx" && Core.devices.isEmpty() && Core.session == null) startSearch()
        syncUi()
    }

    override fun onPause() {
        super.onPause()
        if (Core.ui === this) Core.ui = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Rtc.onVideo = null
        Rtc.attachSink(null)
        renderer.release()
    }

    // ---------- Ayudas de interfaz ----------

    private fun dp(v: Int): Int = (v * sc * resources.displayMetrics.density).toInt()

    private fun label(s: String, size: Float = 16f, bold: Boolean = false, center: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size * sc
            setTextColor(TXT)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (center) gravity = Gravity.CENTER
        }

    private fun shape(c1: Int, c2: Int, radius: Int, stroke: Int, sw: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2)).apply {
            cornerRadius = radius.toFloat()
            if (sw > 0) setStroke(sw, stroke)
        }

    private fun btnBg(c1: Int, c2: Int, radius: Int, stroke: Int): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(c1, c2, radius, Color.WHITE, dp(3)))
            addState(intArrayOf(), shape(c1, c2, radius, stroke, dp(2)))
        }

    private fun button(text: String, c1: Int, c2: Int = c1, textColor: Int = Color.BLACK, stroke: Int = Color.TRANSPARENT,
                       radius: Int = 10, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f * sc
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            background = btnBg(c1, c2, dp(radius), stroke)
            stateListAnimator = null
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, left: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(left, top, 0, 0) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- Pantalla de inicio (según el diseño) ----------

    private fun buildHome(): ScrollView {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }
        page.addView(label("ScreenLink", 36f, true, true), lp(-1, -2))

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // --- Izquierda: nombre, modo, búsqueda ---
        left.addView(label("Nombre de este dispositivo", 19f, true, true), lp(-1, -2, top = dp(10)))
        val nameRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        nameEdit = EditText(this).apply {
            setText(Core.deviceName)
            setTextColor(TXT)
            textSize = 16f * sc
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(40))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.WHITE)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        nameRow.addView(nameEdit, lp(0, -2, 1f))
        nameRow.addView(button("Guardar", YELLOW, stroke = Color.parseColor("#3AA0C8"), radius = 6) {
            Core.rename(nameEdit.text.toString())
            refreshDevices()
            toast("Nombre guardado. Los receptores te verán con ese nombre.")
        }, lp(-2, -2, left = dp(12)))
        left.addView(nameRow, lp(-1, -2, top = dp(6)))

        left.addView(label("Modo de trabajo", 19f, true, true), lp(-1, -2, top = dp(18)))
        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER }
        btnTx = button("Transmisor", Color.WHITE, onClick = { chooseMode("tx") })
        btnRx = button("Receptor", Color.WHITE, onClick = { chooseMode("rx") })
        modes.addView(btnTx)
        modes.addView(btnRx, lp(-2, -2, left = dp(14)))
        left.addView(modes, lp(-1, -2, top = dp(8)))

        // Receptor: control total + búsqueda
        rxBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        controlCheck = CheckBox(this).apply {
            text = "Control total del transmisor"
            setTextColor(TXT)
            textSize = 15f * sc
            isChecked = true
        }
        rxBox.addView(controlCheck, lp(-2, -2, top = dp(14)))
        rxBox.addView(
            button("Iniciar búsqueda de dispositivos", BLUE, stroke = Color.parseColor("#3AA0C8")) { startSearch() },
            lp(-1, -2, top = dp(10))
        )
        left.addView(rxBox, lp(-1, -2))

        // Transmisor: estado del control remoto
        txBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        accStatus = label("", 13f, center = true)
        txBox.addView(accStatus, lp(-1, -2, top = dp(14)))
        txBox.addView(
            button("Activar control remoto (Accesibilidad)", BLUE, stroke = Color.parseColor("#3AA0C8")) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            lp(-1, -2, top = dp(8))
        )
        left.addView(txBox, lp(-1, -2))

        left.addView(
            button("Olvidar dispositivos de confianza", Color.TRANSPARENT, textColor = Color.parseColor("#CDD6EF")) {
                Core.clearTrusted()
                toast("Se pedirá permiso de nuevo en cada conexión.")
            },
            lp(-2, -2, top = dp(12))
        )

        // --- Derecha: lista de dispositivos ---
        right.addView(label("Dispositivos en tu red Wi-Fi", 19f, true, true), lp(-1, -2, top = dp(10)))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(Color.parseColor("#A3B6C7"), Color.parseColor("#7B96AF"), dp(26), EDGE, dp(3))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val scroll = ScrollView(this)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hint = TextView(this).apply {
            textSize = 15f * sc
            setTextColor(Color.parseColor("#10243D"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(24), dp(8), dp(24))
        }
        inner.addView(listBox)
        inner.addView(hint, lp(-1, -2))
        scroll.addView(inner)
        panel.addView(scroll, lp(-1, 0, 1f))

        manualRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        ipEdit = EditText(this).apply {
            hint = "IP manual, ej. 192.168.1.25"
            setHintTextColor(Color.parseColor("#55667A"))
            setTextColor(Color.BLACK)
            textSize = 14f * sc
            setSingleLine()
            inputType = InputType.TYPE_CLASS_PHONE
        }
        manualRow.addView(ipEdit, lp(0, -2, 1f))
        manualRow.addView(button("Conectar", Color.WHITE, stroke = Color.parseColor("#5D7694")) {
            val ip = ipEdit.text.toString().trim()
            if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip)) toast("Escribe una IP válida, por ejemplo 192.168.1.25")
            else connectTo(ip, 48200, ip)
        }, lp(-2, -2, left = dp(8)))
        panel.addView(manualRow, lp(-1, -2, top = dp(8)))

        right.addView(panel, lp(-1, if (wide) 0 else dp(300), if (wide) 1f else 0f, top = dp(8)))

        // --- Disposición: horizontal (PC/TV/tablet) o vertical (celular) ---
        if (wide) {
            val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            cols.addView(left, lp(0, -1, 1f))
            cols.addView(right, lp(0, -1, 1f, left = dp(24)))
            page.addView(cols, lp(-1, 0, 1f))
        } else {
            page.addView(left, lp(-1, -2))
            page.addView(right, lp(-1, -2, top = dp(10)))
        }

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(page, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun chooseMode(m: String) {
        Core.changeMode(m)
        searching = m == "rx"
        refreshMode()
        refreshDevices()
    }

    private fun startSearch() {
        searching = true
        Core.discovery.startSearch()
        refreshDevices()
    }

    private fun refreshMode() {
        val tx = Core.mode == "tx"
        btnTx.background = if (tx) btnBg(Color.parseColor("#B4DFAA"), Color.parseColor("#72B874"), dp(10), Color.parseColor("#2E9BD0"))
        else btnBg(Color.TRANSPARENT, Color.TRANSPARENT, dp(10), MODE)
        btnRx.background = if (!tx) btnBg(Color.parseColor("#B4DFAA"), Color.parseColor("#72B874"), dp(10), Color.parseColor("#2E9BD0"))
        else btnBg(Color.TRANSPARENT, Color.TRANSPARENT, dp(10), MODE)
        btnTx.setTextColor(if (tx) Color.BLACK else Color.WHITE)
        btnRx.setTextColor(if (!tx) Color.BLACK else Color.WHITE)
        rxBox.visibility = if (tx) View.GONE else View.VISIBLE
        txBox.visibility = if (tx) View.VISIBLE else View.GONE
        manualRow.visibility = if (tx) View.GONE else View.VISIBLE
    }

    private fun refreshAccessibility() {
        val on = ControlService.isEnabled(this)
        accStatus.text = if (on) {
            "Control remoto activado. En cada conexión tú decides si lo permites."
        } else {
            "Control remoto desactivado: actívalo en Accesibilidad > ScreenLink para que el receptor pueda manejar este dispositivo. " +
                "Sin esto solo podrán ver tu pantalla."
        }
    }

    private fun platformLabel(p: String) = when (p) {
        "windows" -> "PC Windows"
        "android" -> "Celular Android"
        "androidtv" -> "TV Android"
        else -> "Dispositivo"
    }

    private fun refreshDevices() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        if (Core.mode == "tx") {
            hint.text = "Visible como \"${Core.deviceName}\".\nEsperando que un receptor se conecte…"
            hint.visibility = View.VISIBLE
            return
        }
        val list = Core.devices.values.sortedBy { it.name.lowercase() }
        hint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        hint.text = if (searching) "Buscando transmisores…\nAbre ScreenLink en el otro equipo en modo Transmisor."
        else "Pulsa \"Iniciar búsqueda de dispositivos\"."
        for (d in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = btnBg(Color.parseColor("#F2F6FA"), Color.parseColor("#F2F6FA"), dp(12), Color.TRANSPARENT)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isFocusable = true
                isClickable = true
                setOnClickListener { connectTo(d.host, d.port, d.name) }
            }
            row.addView(TextView(this).apply {
                text = d.name
                textSize = 17f * sc
                setTextColor(Color.BLACK)
                setTypeface(typeface, Typeface.BOLD)
            })
            row.addView(TextView(this).apply {
                text = "${platformLabel(d.platform)} · ${d.host}"
                textSize = 12f * sc
                setTextColor(Color.parseColor("#445566"))
            })
            listBox.addView(row, lp(-1, -2, top = dp(8)))
        }
    }

    /** Toca un dispositivo de la lista: se conecta de inmediato. */
    private fun connectTo(host: String, port: Int, name: String) {
        val err = Core.connect(host, port, controlCheck.isChecked, name)
        if (err != null) {
            toast(err)
            return
        }
        showSessionView()
        resetSessionWidgets()
        infoText.text = name
        waitingText.text = "Esperando que \"$name\" acepte…"
        waitingText.visibility = View.VISIBLE
        showBar(true)
    }

    // ---------- Captura de pantalla (permiso de Android) ----------

    private fun requestProjection(cb: (Intent?) -> Unit) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionCallback = cb
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
        } catch (_: Exception) {
            projectionCallback = null
            toast("Este dispositivo no permite compartir su pantalla (algunas TV no incluyen esta función).")
            cb(null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            val cb = projectionCallback
            projectionCallback = null
            cb?.invoke(if (resultCode == RESULT_OK) data else null)
        }
    }

    // ---------- Pantalla de sesión ----------

    private fun buildSession(): FrameLayout {
        val f = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        renderer = FillRenderer(this)
        renderer.setOnTouchListener { _, e ->
            if (canControl() && !Core.isTv) onTouch(e) else {
                if (e.actionMasked == MotionEvent.ACTION_UP) showBar(!barShown) // sin control: tocar muestra u oculta la barra
                true
            }
        }
        renderer.setOnGenericMotionListener { _, e -> if (canControl()) onMouse(e) else false }
        f.addView(renderer, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        cursor = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(210, 255, 255, 255))
                setStroke(dp(2), Color.BLACK)
            }
            visibility = View.GONE
        }
        f.addView(cursor, FrameLayout.LayoutParams(dp(18), dp(18)))

        waitingText = label("Conectando…", 18f, center = true)
        f.addView(waitingText, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        sharingText = label("", 20f, center = true).apply {
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(BG)
            visibility = View.GONE
        }
        f.addView(sharingText, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        infoText = label("", 15f, true)
        bar.addView(infoText, lp(0, -2, 1f))
        fitBtn = button("Ajuste: llenar", Color.WHITE) {
            renderer.stretch = !renderer.stretch
            fitBtn.text = if (renderer.stretch) "Ajuste: llenar" else "Ajuste: proporcional"
        }
        rightBtn = button("Clic derecho: no", Color.WHITE) {
            rightMode = !rightMode
            rightBtn.text = if (rightMode) "Clic derecho: sí" else "Clic derecho: no"
        }
        keyboardBtn = button("Teclado", Color.WHITE) { toggleKeyboard() }
        bar.addView(fitBtn, lp(-2, -2, left = dp(6)))
        bar.addView(rightBtn, lp(-2, -2, left = dp(6)))
        bar.addView(keyboardBtn, lp(-2, -2, left = dp(6)))
        bar.addView(
            button("Terminar", DANGER, textColor = Color.WHITE) { Core.endSession("Sesión terminada.") },
            lp(-2, -2, left = dp(6))
        )
        f.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Campo invisible para recibir el teclado en pantalla del celular
        hiddenInput = EditText(this).apply {
            alpha = 0f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val t = s?.toString() ?: return
                    if (t.isEmpty()) return
                    s.clear()
                    Rtc.sendControl(JSONObject().put("k", "text").put("s", t))
                }
            })
            setOnKeyListener { _, code, e ->
                val vk = when (code) {
                    KeyEvent.KEYCODE_DEL -> 8
                    KeyEvent.KEYCODE_ENTER -> 13
                    else -> 0
                }
                if (vk != 0 && e.action == KeyEvent.ACTION_DOWN) {
                    sendKey(vk, true)
                    sendKey(vk, false)
                }
                vk != 0
            }
        }
        f.addView(hiddenInput, FrameLayout.LayoutParams(dp(2), dp(2), Gravity.BOTTOM))
        return f
    }

    private fun showBar(show: Boolean) {
        barShown = show
        bar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            // Con mando (TV), el foco pasa a la barra para poder pulsar "Terminar".
            (bar.getChildAt(bar.childCount - 1) as? Button)?.requestFocus()
        }
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (kbShown) {
            imm.hideSoftInputFromWindow(hiddenInput.windowToken, 0)
            kbShown = false
        } else {
            hiddenInput.requestFocus()
            @Suppress("DEPRECATION")
            imm.showSoftInput(hiddenInput, InputMethodManager.SHOW_FORCED)
            kbShown = true
        }
    }

    private fun applyImmersive(on: Boolean) {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else View.SYSTEM_UI_FLAG_VISIBLE
        if (Build.VERSION.SDK_INT >= 28) {
            val a = window.attributes
            a.layoutInDisplayCutoutMode =
                if (on) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            window.attributes = a
        }
    }

    private fun showHome() {
        inSession = false
        homeView.visibility = View.VISIBLE
        sessionView.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive(false)
        // Celular: vertical. Tablet y TV: horizontal.
        requestedOrientation = if (wide) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun showSessionView() {
        inSession = true
        homeView.visibility = View.GONE
        sessionView.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive(true) // pantalla completa, sin barras del sistema
        if (!wide) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    private fun resetSessionWidgets() {
        sharingText.visibility = View.GONE
        cursor.visibility = View.GONE
        fitBtn.visibility = View.GONE
        rightBtn.visibility = View.GONE
        keyboardBtn.visibility = View.GONE
        rightMode = false
        rightBtn.text = "Clic derecho: no"
        kbShown = false
        dragging = false
        scrolling = false
        cx = 0.5f
        cy = 0.5f
        renderer.stretch = true
        fitBtn.text = "Ajuste: llenar"
    }

    /** Alinea la pantalla con el estado del núcleo (útil al volver a la app). */
    private fun syncUi() {
        val s = Core.session
        when {
            s != null && s.active -> enterSession(s)
            s != null -> showSessionView()
            else -> showHome()
        }
    }

    private fun canControl(): Boolean {
        val s = Core.session ?: return false
        return s.active && s.role == Role.VIEWER && s.control
    }

    private fun enterSession(s: Session) {
        showSessionView()
        resetSessionWidgets()
        if (s.role == Role.VIEWER) {
            infoText.text = "Viendo: ${s.peerName}"
            waitingText.text = "Recibiendo pantalla…"
            waitingText.visibility = if (Rtc.remoteTrack == null) View.VISIBLE else View.GONE
            fitBtn.visibility = View.VISIBLE
            if (canControl()) {
                if (Core.isTv) {
                    cursor.visibility = View.VISIBLE
                    updateCursor()
                } else {
                    rightBtn.visibility = View.VISIBLE
                    keyboardBtn.visibility = View.VISIBLE
                }
            }
            showBar(true)
            Core.main.postDelayed({ if (inSession && Core.session?.active == true) showBar(false) }, 3500)
        } else {
            infoText.text = "Compartiendo con: ${s.peerName}"
            waitingText.visibility = View.GONE
            sharingText.visibility = View.VISIBLE
            sharingText.text = if (s.control) {
                "Estás compartiendo tu pantalla.\n\n${s.peerName} puede ver y CONTROLAR este dispositivo."
            } else {
                "Estás compartiendo tu pantalla.\n\n${s.peerName} solo puede verla."
            }
            showBar(true)
        }
    }

    private fun updateVideoState() {
        if (Rtc.remoteTrack != null) waitingText.visibility = View.GONE
        updateCursor()
    }

    // ---------- Control táctil (celular/tablet) ----------
    // La vista del vídeo ocupa exactamente el área de la pantalla remota, así que x/ancho e y/alto bastan.

    private fun send(kind: String, x: Float, y: Float, b: Int = 0) {
        Rtc.sendControl(JSONObject().put("k", kind).put("x", x.toDouble()).put("y", y.toDouble()).put("b", b))
    }

    private fun sendKey(vk: Int, down: Boolean) {
        Rtc.sendControl(JSONObject().put("k", "key").put("d", down).put("vk", vk))
    }

    private fun nx(x: Float) = (x / max(1, renderer.width)).coerceIn(0f, 1f)
    private fun ny(y: Float) = (y / max(1, renderer.height)).coerceIn(0f, 1f)

    private fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrolling = false
                dragging = true
                val mouseSecondary = e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE &&
                    (e.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                touchButton = if (rightMode || mouseSecondary) 2 else 0
                send("down", nx(e.x), ny(e.y), touchButton)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Dos dedos = desplazar (rueda)
                if (dragging) {
                    send("up", nx(e.getX(0)), ny(e.getY(0)), touchButton)
                    dragging = false
                }
                scrolling = true
                lastScrollY = (e.getY(0) + e.getY(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling && e.pointerCount >= 2) {
                    val y = (e.getY(0) + e.getY(1)) / 2f
                    val dy = lastScrollY - y
                    if (abs(dy) > 4f) {
                        Rtc.sendControl(JSONObject().put("k", "wheel").put("dx", 0).put("dy", (dy * 2).toInt()))
                        lastScrollY = y
                    }
                } else if (dragging) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastMoveAt >= 16) {
                        lastMoveAt = now
                        send("move", nx(e.x), ny(e.y))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) send("up", nx(e.x), ny(e.y), touchButton)
                dragging = false
                scrolling = false
                if (rightMode) {
                    rightMode = false
                    rightBtn.text = "Clic derecho: no"
                }
            }
        }
        return true
    }

    // Ratón Bluetooth/USB del receptor: movimiento y rueda.
    private fun onMouse(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastMoveAt >= 16) {
                    lastMoveAt = now
                    send("move", nx(e.x), ny(e.y))
                }
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) Rtc.sendControl(JSONObject().put("k", "wheel").put("dx", 0).put("dy", (-v * 120).toInt()))
            }
            else -> return false
        }
        return true
    }

    // ---------- Control con mando (TV) y teclado físico/Bluetooth ----------

    private fun updateCursor() {
        if (cursor.visibility != View.VISIBLE || renderer.width == 0) return
        cursor.x = renderer.x + cx * renderer.width - cursor.width / 2f
        cursor.y = renderer.y + cy * renderer.height - cursor.height / 2f
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cx = (cx + dx).coerceIn(0f, 1f)
        cy = (cy + dy).coerceIn(0f, 1f)
        send("move", cx, cy)
        updateCursor()
    }

    private fun vkOf(code: Int): Int = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 65 + (code - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 48 + (code - KeyEvent.KEYCODE_0)
        KeyEvent.KEYCODE_SPACE -> 32
        KeyEvent.KEYCODE_ENTER -> 13
        KeyEvent.KEYCODE_DEL -> 8
        KeyEvent.KEYCODE_FORWARD_DEL -> 46
        KeyEvent.KEYCODE_TAB -> 9
        KeyEvent.KEYCODE_ESCAPE -> 27
        KeyEvent.KEYCODE_DPAD_LEFT -> 37
        KeyEvent.KEYCODE_DPAD_UP -> 38
        KeyEvent.KEYCODE_DPAD_RIGHT -> 39
        KeyEvent.KEYCODE_DPAD_DOWN -> 40
        KeyEvent.KEYCODE_MOVE_HOME -> 36
        KeyEvent.KEYCODE_MOVE_END -> 35
        KeyEvent.KEYCODE_PAGE_UP -> 33
        KeyEvent.KEYCODE_PAGE_DOWN -> 34
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 16
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 17
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> 18
        KeyEvent.KEYCODE_COMMA -> 188
        KeyEvent.KEYCODE_PERIOD -> 190
        KeyEvent.KEYCODE_MINUS -> 189
        KeyEvent.KEYCODE_SLASH -> 191
        else -> 0
    }

    private fun isPhysicalKeyboard(e: KeyEvent): Boolean =
        e.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC

    private fun handleTvKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val step = 0.008f * (1f + min(e.repeatCount, 24) / 3f)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) moveCursor(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) moveCursor(step, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> if (down) moveCursor(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) moveCursor(0f, step)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && e.repeatCount == 0) send("down", cx, cy, 0)
                if (e.action == KeyEvent.ACTION_UP) send("up", cx, cy, 0)
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (down && e.repeatCount == 0) {
                send("down", cx, cy, 2) // clic derecho
                send("up", cx, cy, 2)
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) Rtc.sendControl(JSONObject().put("k", "wheel").put("dx", 0).put("dy", 120))
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) Rtc.sendControl(JSONObject().put("k", "wheel").put("dx", 0).put("dy", -120))
            else -> return false
        }
        return true
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (inSession && Core.session != null) {
            // Atrás: muestra u oculta la barra (con "Terminar"). No cierra la sesión por accidente.
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                if (e.action == KeyEvent.ACTION_UP) {
                    if (kbShown) toggleKeyboard() else showBar(!barShown)
                }
                return true
            }
            if (canControl() && !(barShown && Core.isTv)) {
                // Teclado físico/Bluetooth del receptor: se envía tal cual al transmisor.
                if (isPhysicalKeyboard(e)) {
                    val vk = vkOf(e.keyCode)
                    if (vk != 0) {
                        sendKey(vk, e.action == KeyEvent.ACTION_DOWN)
                        return true
                    }
                }
                if (Core.isTv && handleTvKey(e)) return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------- UiListener (avisos del núcleo) ----------

    override fun onDevices() {
        refreshDevices()
    }

    override fun onIncoming(req: Incoming, decide: (Boolean, Boolean) -> Unit) {
        val msg = buildString {
            append("\"${req.peerName}\" quiere ver tu pantalla.")
            if (req.wantsControl) append("\nTambién solicita CONTROL TOTAL de este dispositivo.")
            if (req.wantsControl && !ControlService.isEnabled(this@MainActivity)) {
                append("\n\nAviso: el control remoto no está activado en Accesibilidad; solo podrán ver tu pantalla hasta que lo actives.")
            }
            append("\n\nSolo acepta si reconoces este dispositivo.")
        }
        AlertDialog.Builder(this)
            .setTitle("Solicitud de conexión")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Permitir") { _, _ -> decide(true, false) }
            .setNeutralButton("Permitir siempre") { _, _ -> decide(true, true) }
            .setNegativeButton("Rechazar") { _, _ -> decide(false, false) }
            .show()
    }

    override fun onNeedProjection(result: (Intent?) -> Unit) {
        requestProjection(result)
    }

    override fun onSessionStart(s: Session) {
        enterSession(s)
    }

    override fun onSessionEnd(reason: String) {
        renderer.clear()
        showHome()
        refreshDevices()
        if (reason.isNotEmpty()) toast(reason)
    }

    companion object {
        private const val REQ_PROJECTION = 42
    }
}
