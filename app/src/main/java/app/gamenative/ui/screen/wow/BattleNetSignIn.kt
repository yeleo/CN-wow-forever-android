package app.gamenative.ui.screen.wow

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.gamenative.ui.util.SnackbarManager
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.Crypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
            file.appendText(line)
        } catch (e: Exception) {
            Timber.w(e, "Failed to write launcher debug log")
        }
        Timber.i("[$tag] $message")
    }
}

object BattleNetSignIn {
    data class Login(val email: String, val password: String)

    fun load(context: Context): Login? {
        val prefs = prefs(context)
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val sealed = prefs.getString(KEY_PASSWORD, null) ?: return null
        return try {
            Login(email, String(Crypto.decrypt(Base64.decode(sealed, Base64.NO_WRAP))))
        } catch (e: Exception) {
            Timber.w(e, "Saved Battle.net login unreadable, clearing it")
            forget(context)
            null
        }
    }

    fun save(context: Context, login: Login) {
        val sealed = Base64.encodeToString(Crypto.encrypt(login.password.toByteArray()), Base64.NO_WRAP)
        prefs(context).edit().putString(KEY_EMAIL, login.email).putString(KEY_PASSWORD, sealed).apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun writeLoginFile(gameRoot: File, login: Login) {
        try {
            val flavorDir = File(gameRoot, WowClientDownloader.FLAVOR_DIR)
            val file = File(flavorDir, "login.txt")
            file.parentFile?.mkdirs()
            val text = "${login.email}\n${login.password}\n"
            file.writeText(text)
            val logsDir = File(flavorDir, "Logs")
            logsDir.mkdirs()
            LauncherDebugLogger.setLogDirectory(logsDir)
            LauncherDebugLogger.log("LOGIN_TXT", "login.txt written (size: ${text.toByteArray().size} bytes, flavorDir: ${WowClientDownloader.FLAVOR_DIR})")
        } catch (e: Exception) {
            Timber.e(e, "Failed to write login.txt")
            LauncherDebugLogger.log("LOGIN_TXT", "Failed to write login.txt: ${e.message}")
        }
    }

    // Also clears a login.txt left in the beta folder, which the full release no longer reads.
    fun removeLoginFile(gameRoot: File, reason: String = "manual/exit") {
        listOf(WowClientDownloader.FLAVOR_DIR, WowClientDownloader.LEGACY_FLAVOR_DIR).forEach { flavorDir ->
            try {
                val file = File(File(gameRoot, flavorDir), "login.txt")
                if (file.exists()) {
                    val originalSize = file.length()
                    file.writeBytes(ByteArray(originalSize.coerceAtMost(4096).toInt()))
                    file.delete()
                    LauncherDebugLogger.log(
                        "LOGIN_TXT",
                        "login.txt wiped and deleted (flavorDir: $flavorDir, size: $originalSize bytes, reason: $reason)"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove login.txt")
                LauncherDebugLogger.log("LOGIN_TXT", "Failed to remove login.txt: ${e.message}")
            }
        }
    }

    fun removeLoginFile(context: Context) {
        watcherJob?.cancel()
        try {
            val path = GamePath.load(context)
            removeLoginFile(File(path), reason = "app event/shutdown")
        } catch (_: Exception) {}
        try {
            val defaultPath = File(android.os.Environment.getExternalStorageDirectory(), "WoW Forever")
            removeLoginFile(defaultPath, reason = "app event/shutdown")
        } catch (_: Exception) {}
    }

    private var watcherJob: Job? = null

    fun startLoginWatcher(gameRoot: File) {
        watcherJob?.cancel()
        val logsDir = File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/Logs")
        logsDir.mkdirs()
        LauncherDebugLogger.setLogDirectory(logsDir)
        LauncherDebugLogger.log("LOGIN_TXT", "startLoginWatcher initiated (deadline: 180s)")

        watcherJob = CoroutineScope(Dispatchers.IO).launch {
            val hotfixLog = File(logsDir, "Hotfix.log")
            val connectionLog = File(logsDir, "connection.log")

            // Rotate existing connection.log so we only detect events from this new launch session
            if (connectionLog.exists() && connectionLog.length() > 0) {
                try {
                    val oldLog = File(logsDir, "connection.old.log")
                    if (oldLog.exists()) oldLog.delete()
                    connectionLog.renameTo(oldLog)
                    LauncherDebugLogger.log("LOGIN_TXT", "Rotated previous connection.log to connection.old.log")
                } catch (e: Exception) {
                    Timber.w(e, "Failed to rotate connection.log")
                }
            }

            // Fallback deadline: 180 seconds (3 minutes) as required
            val deadline = System.currentTimeMillis() + 180_000L
            var detectedReason: String? = null

            while (isActive && System.currentTimeMillis() < deadline) {
                delay(1000L)

                // 1. Check connection.log for Battle.net login request
                if (connectionLog.exists() && connectionLog.length() > 0) {
                    try {
                        val text = connectionLog.readText()
                        if (text.contains("Battle.net logon requested") || text.contains("Glue login started")) {
                            detectedReason = "detected 'Battle.net logon requested' in connection.log"
                            break
                        }
                    } catch (_: Exception) {}
                }

                // 2. Check Hotfix.log for asset streaming completion
                if (hotfixLog.exists() && hotfixLog.length() > 50_000L) {
                    detectedReason = "Hotfix.log exceeded 50KB"
                    break
                }
            }

            if (detectedReason != null) {
                LauncherDebugLogger.log("LOGIN_TXT", "Game consumed login: $detectedReason. Waiting 2s before wipe...")
                delay(2000L)
                removeLoginFile(gameRoot, reason = detectedReason)
            } else {
                LauncherDebugLogger.log("LOGIN_TXT", "Watcher deadline reached (180s). Wiping login.txt...")
                removeLoginFile(gameRoot, reason = "fallback timeout (180s)")
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "battle_net_login"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "password"
    private const val KEY_WEB_TOKEN = "web_token"
    private const val KEY_WEB_TOKEN_TS = "web_token_ts"

    val ST_REGEX = Regex("""^CN-[0-9a-fA-F]{32}-\d+$""")
    const val ST_EXPIRATION_MINUTES = 60L
    var KEEP_ST_AFTER_USE = true

    fun hasCredentials(context: Context): Boolean = load(context) != null

    fun loadAccountEmail(context: Context): String? = load(context)?.email

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

    fun saveWebToken(context: Context, token: String) {
        val ts = System.currentTimeMillis()
        prefs(context).edit()
            .putString(KEY_WEB_TOKEN, token)
            .putLong(KEY_WEB_TOKEN_TS, ts)
            .apply()
        LauncherDebugLogger.log(
            "WEB_AUTH",
            "Saved ST token to SharedPreferences (length=${token.length}, prefix=${token.take(4)}***, captureTs=$ts)",
        )
    }

    fun clearWebToken(context: Context) {
        prefs(context).edit()
            .remove(KEY_WEB_TOKEN)
            .remove(KEY_WEB_TOKEN_TS)
            .apply()
        LauncherDebugLogger.log("TOKEN_INJECT", "Cleared web token from SharedPreferences")
    }
}

@Composable
fun BattleNetCredentialDialog(
    onDismiss: () -> Unit,
    onConfirm: (BattleNetSignIn.Login) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val emailFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(DIALOG_SETTLE_MS)
        emailFocus.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Battle.net login") },
        text = {
            Column {
                Text("Saved encrypted on this device. WoW will automatically log in on launch.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(emailFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = email.isNotBlank() && password.isNotEmpty(),
                onClick = { onConfirm(BattleNetSignIn.Login(email.trim(), password)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val DIALOG_SETTLE_MS = 300L

private const val AUTH_URL = "https://account.battlenet.com.cn/login/zh/?externalChallenge=login&app=wow"

@Composable
fun BattleNetWebAuthDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var currentUrl by remember { mutableStateOf(AUTH_URL) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var hasCapturedToken by remember { mutableStateOf(!BattleNetSignIn.loadWebToken(context).isNullOrBlank()) }
    var showAccountPrompt by remember { mutableStateOf(false) }
    var accountInput by remember { mutableStateOf("") }

    if (showAccountPrompt) {
        AlertDialog(
            onDismissRequest = {
                showAccountPrompt = false
                onDismiss()
            },
            title = { Text("设置战网账号") },
            text = {
                Column {
                    Text("授信 Token 已成功捕获！请输入该 Token 对应的战网账号（邮箱或手机号），以用于完成凭据注入：")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = accountInput,
                        onValueChange = { accountInput = it },
                        label = { Text("战网账号 / 邮箱") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = accountInput.isNotBlank(),
                    onClick = {
                        val trimmed = accountInput.trim()
                        BattleNetSignIn.save(context, BattleNetSignIn.Login(trimmed, ""))
                        showAccountPrompt = false
                        onDismiss()
                    },
                ) {
                    Text("保存并完成")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAccountPrompt = false
                    onDismiss()
                }) {
                    Text("跳过")
                }
            },
        )
    }

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
                            text = if (hasCapturedToken) "状态: 已成功捕获有效 ST Token" else "请在下方登录网易通行证以完成授权挑战",
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
                                            val saved = BattleNetSignIn.load(context)
                                            if (saved == null || saved.email.isBlank()) {
                                                showAccountPrompt = true
                                            } else {
                                                onDismiss()
                                            }
                                            return true
                                        } else {
                                            SnackbarManager.show("捕获的授信票据格式异常，已丢弃")
                                            LauncherDebugLogger.log(
                                                "WEB_AUTH",
                                                "Token format regex mismatch, dropped: len=${token.length}, prefix=${token.take(4)}***",
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
