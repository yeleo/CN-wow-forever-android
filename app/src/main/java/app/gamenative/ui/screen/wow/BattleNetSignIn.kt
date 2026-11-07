package app.gamenative.ui.screen.wow

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.ui.util.SnackbarManager
import timber.log.Timber
import java.io.File

object LauncherDebugLogger {
    private val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
    private var customLogDir: File? = null

    fun setLogDirectory(dir: File) {
        customLogDir = dir
    }

    private fun getLogFile(): File {
        val baseDir = customLogDir ?: File(android.os.Environment.getExternalStorageDirectory(), "WoW Forever/${WowClientDownloader.FLAVOR_DIR}/Logs")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        return File(baseDir, "launcher_login_debug.log")
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(java.util.Date())
        val line = "[$timestamp] [$tag] $message\n"
        try {
            val file = getLogFile()
            java.io.FileOutputStream(file, true).use { fos ->
                val bytes = line.toByteArray(Charsets.UTF_8)
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to write launcher debug log")
        }
        Timber.i("[$tag] $message")
    }

    fun logException(tag: String, stage: String, t: Throwable) {
        val stackTrace = t.stackTrace.take(30).joinToString("\n    at ") { it.toString() }
        val msg = "FATAL EXCEPTION in $stage: ${t.javaClass.name}: ${t.message}\n    at $stackTrace"
        log(tag, msg)
    }
}

object BattleNetSignIn {
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "battle_net_login"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "password"
    private const val KEY_WEB_TOKEN = "web_token"
    private const val KEY_WEB_TOKEN_TS = "web_token_ts"

    val ST_REGEX = Regex("""^CN-[0-9a-fA-F]{32}-\d+$""")
    const val ST_EXPIRATION_MINUTES = 60L
    var KEEP_ST_AFTER_USE = true

    fun loadWebToken(context: Context): String? =
        prefs(context).getString(KEY_WEB_TOKEN, null)

    fun loadWebTokenTimestamp(context: Context): Long =
        prefs(context).getLong(KEY_WEB_TOKEN_TS, 0L)

    fun isWebTokenExpired(context: Context): Boolean {
        val ts = loadWebTokenTimestamp(context)
        if (ts <= 0L) return true
        val ageMin = (System.currentTimeMillis() - ts) / 60000L
        return ageMin >= ST_EXPIRATION_MINUTES
    }

    fun hasValidWebToken(context: Context): Boolean {
        val token = loadWebToken(context)
        return !token.isNullOrBlank() && ST_REGEX.matches(token) && !isWebTokenExpired(context)
    }

    fun getRemainingValidMinutes(context: Context): Long {
        val ts = loadWebTokenTimestamp(context)
        if (ts <= 0L) return 0L
        val ageMin = (System.currentTimeMillis() - ts) / 60000L
        return (ST_EXPIRATION_MINUTES - ageMin).coerceAtLeast(0L)
    }

    fun saveWebToken(context: Context, token: String) {
        val ts = System.currentTimeMillis()
        prefs(context).edit()
            .putString(KEY_WEB_TOKEN, token)
            .putLong(KEY_WEB_TOKEN_TS, ts)
            .apply()
        LauncherDebugLogger.log(
            "WEB_AUTH",
            "Saved ST token to SharedPreferences (length=${token.length}, regexMatch=true, captureTs=$ts)",
        )
    }

    fun clearWebToken(context: Context) {
        prefs(context).edit()
            .remove(KEY_WEB_TOKEN)
            .remove(KEY_WEB_TOKEN_TS)
            .apply()
        LauncherDebugLogger.log("TOKEN_INJECT", "Cleared web token from SharedPreferences")
    }

    fun cleanupLegacyCredentials(context: Context, gameRoot: File? = null) {
        try {
            // 1. Clear SharedPreferences email & password
            val p = prefs(context)
            if (p.contains(KEY_EMAIL) || p.contains(KEY_PASSWORD)) {
                p.edit().remove(KEY_EMAIL).remove(KEY_PASSWORD).apply()
            }

            // 2. Clear KeyStore secret entry if present
            runCatching {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias("pluvia_secret")) {
                    ks.deleteEntry("pluvia_secret")
                }
            }

            // 3. Search and zero-fill wipe legacy login.txt files
            val roots = mutableListOf<File>()
            gameRoot?.let { roots.add(it) }
            runCatching {
                val saved = GamePath.load(context)
                if (saved.isNotBlank()) roots.add(File(saved))
            }
            roots.add(File(android.os.Environment.getExternalStorageDirectory(), "WoW Forever"))

            val flavors = listOf(WowClientDownloader.FLAVOR_DIR, WowClientDownloader.LEGACY_FLAVOR_DIR)
            for (root in roots.distinct()) {
                for (flv in flavors) {
                    val loginFile = File(File(root, flv), "login.txt")
                    if (loginFile.exists()) {
                        val len = loginFile.length()
                        loginFile.writeBytes(ByteArray(len.coerceAtMost(4096).toInt()))
                        loginFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error during legacy credentials cleanup")
        }
        LauncherDebugLogger.log("LEGACY_CLEANUP", "Legacy credentials cleaned up: no sensitive data remaining")
    }
}

private const val AUTH_URL = "https://account.battlenet.com.cn/login/zh/?externalChallenge=login&app=wow"

@Composable
fun BattleNetWebAuthDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var currentUrl by remember { mutableStateOf(AUTH_URL) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var hasCapturedToken by remember { mutableStateOf(BattleNetSignIn.hasValidWebToken(context)) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 28.dp, bottom = 16.dp, start = 10.dp, end = 10.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F172A),
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "国服战网网页授信 (解除14400风控)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF59E0B),
                        )
                        Text(
                            text = if (hasCapturedToken) "状态: 已成功捕获有效 ST 票据" else "请在下方登录网易通行证以完成授权挑战",
                            fontSize = 11.sp,
                            color = if (hasCapturedToken) Color(0xFF22C55E) else Color(0xFF94A3B8),
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Action Shortcuts Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E2433))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            currentUrl = AUTH_URL
                            webViewInstance?.loadUrl(currentUrl)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("重新加载授权页", fontSize = 12.sp, color = Color(0xFF38BDF8))
                    }

                    TextButton(
                        onClick = { webViewInstance?.reload() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("刷新", fontSize = 12.sp, color = Color(0xFFE2E8F0))
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFF59E0B))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("外部浏览器", fontSize = 11.sp, color = Color(0xFFF59E0B))
                    }
                }

                // Info banner
                Text(
                    text = "说明：在此登录网易通行证并完成短信或滑块验证。授权成功后将自动捕获 ST 票据并在 Wine 容器内完成 DPAPI 加密注入，解除 14400 风控拦截。",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )

                // WebView Container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewInstance = this
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.userAgentString =
                                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                fun checkAndHandleToken(urlString: String, uri: Uri?): Boolean {
                                    val targetUri = uri ?: runCatching { Uri.parse(urlString) }.getOrNull() ?: return false
                                    var token = targetUri.getQueryParameter("ST")
                                        ?: targetUri.getQueryParameter("st")
                                        ?: targetUri.getQueryParameter("ticket")
                                        ?: targetUri.getQueryParameter("token")

                                    if (token.isNullOrBlank()) {
                                        val match = Regex("""[?&#](?:ST|st|token)=([^&#]+)""").find(urlString)
                                        token = match?.groupValues?.get(1)
                                    }

                                    if (!token.isNullOrBlank()) {
                                        if (token.matches(BattleNetSignIn.ST_REGEX)) {
                                            BattleNetSignIn.saveWebToken(context, token)
                                            hasCapturedToken = true
                                            SnackbarManager.show("已成功捕获国服战网授信 Token！")
                                            onDismiss()
                                            return true
                                        } else {
                                            SnackbarManager.show("捕获的授信票据格式异常，已丢弃")
                                            LauncherDebugLogger.log(
                                                "WEB_AUTH",
                                                "Token format regex mismatch, dropped: len=${token.length}",
                                            )
                                        }
                                    }
                                    return false
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val uri = request?.url ?: return false
                                        val urlString = uri.toString()
                                        currentUrl = urlString
                                        if (urlString.startsWith("http://localhost:0/") ||
                                            urlString.startsWith("http://localhost") ||
                                            urlString.startsWith("https://localhost") ||
                                            urlString.startsWith("battlenet://")
                                        ) {
                                            checkAndHandleToken(urlString, uri)
                                            return true
                                        }
                                        return false
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        url?.let {
                                            currentUrl = it
                                            if (it.startsWith("http://localhost") || it.startsWith("https://localhost")) {
                                                checkAndHandleToken(it, null)
                                            }
                                        }
                                    }

                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                        super.onReceivedError(view, request, error)
                                        val urlString = request?.url?.toString()
                                        if (urlString != null && (urlString.startsWith("http://localhost") || urlString.startsWith("https://localhost"))) {
                                            checkAndHandleToken(urlString, request.url)
                                        }
                                    }
                                }
                                loadUrl(currentUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Bottom Done Button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                ) {
                    Text("完成授信 / 返回启动器", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
