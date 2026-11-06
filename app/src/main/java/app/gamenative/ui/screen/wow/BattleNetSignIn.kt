package app.gamenative.ui.screen.wow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

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
            val file = File(File(gameRoot, WowClientDownloader.FLAVOR_DIR), "login.txt")
            file.parentFile?.mkdirs()
            file.writeText("${login.email}\n${login.password}\n")
        } catch (e: Exception) {
            Timber.e(e, "Failed to write login.txt")
        }
    }

    // Also clears a login.txt left in the beta folder, which the full release no longer reads.
    fun removeLoginFile(gameRoot: File) {
        listOf(WowClientDownloader.FLAVOR_DIR, WowClientDownloader.LEGACY_FLAVOR_DIR).forEach { flavorDir ->
            try {
                val file = File(File(gameRoot, flavorDir), "login.txt")
                if (file.exists()) {
                    file.writeBytes(ByteArray(file.length().coerceAtMost(4096).toInt()))
                    file.delete()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove login.txt")
            }
        }
    }

    fun removeLoginFile(context: Context) {
        try {
            val path = GamePath.load(context)
            removeLoginFile(File(path))
        } catch (_: Exception) {}
        try {
            val defaultPath = File(android.os.Environment.getExternalStorageDirectory(), "WoW Forever")
            removeLoginFile(defaultPath)
        } catch (_: Exception) {}
    }

    fun startLoginWatcher(gameRoot: File) {
        CoroutineScope(Dispatchers.IO).launch {
            val hotfixLog = File(File(gameRoot, WowClientDownloader.FLAVOR_DIR), "Logs/Hotfix.log")
            hotfixLog.delete()
            // WoW client reads login.txt on early startup. Wipe and delete within 25s to avoid exposing credentials.
            val deadline = System.currentTimeMillis() + 25_000L
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(1000L)
                if (hotfixLog.exists() && hotfixLog.length() > 50_000L) {
                    removeLoginFile(gameRoot)
                    return@launch
                }
            }
            removeLoginFile(gameRoot)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "battle_net_login"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "password"
    private const val KEY_WEB_TOKEN = "web_token"

    fun hasCredentials(context: Context): Boolean = load(context) != null

    fun loadWebToken(context: Context): String? =
        prefs(context).getString(KEY_WEB_TOKEN, null)

    fun saveWebToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_WEB_TOKEN, token).apply()
    }

    fun clearWebToken(context: Context) {
        prefs(context).edit().remove(KEY_WEB_TOKEN).apply()
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

@Composable
fun BattleNetWebAuthDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var currentUrl by remember { mutableStateOf("https://account.battlenet.com.cn/") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var hasCapturedToken by remember { mutableStateOf(!BattleNetSignIn.loadWebToken(context).isNullOrBlank()) }

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
                            text = if (hasCapturedToken) "状态: 已成功捕获并保存 SSO Token" else "请在下方登录以解除陌生网络/IP拦截",
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
                            currentUrl = "https://account.battlenet.com.cn/"
                            webViewInstance?.loadUrl(currentUrl)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("战网中心", fontSize = 12.sp, color = Color(0xFF38BDF8))
                    }

                    TextButton(
                        onClick = {
                            currentUrl = "https://cn.account.battle.net/login/zh/?app=wow"
                            webViewInstance?.loadUrl(currentUrl)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("魔兽登录", fontSize = 12.sp, color = Color(0xFF38BDF8))
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
                    text = "说明：若游戏报 BLZ51914400 拦截，说明手机网络受到暴雪/网易风控。在此网页中登录您的战网并通过滑块验证，同一网络IP即可通过授信；若捕获到 SSO Token 将自动保存注入游戏。",
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

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val uri = request?.url ?: return false
                                        val urlString = uri.toString()
                                        currentUrl = urlString
                                        checkToken(ctx, urlString)
                                        return false
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        url?.let {
                                            currentUrl = it
                                            checkToken(ctx, it)
                                        }
                                    }

                                    private fun checkToken(context: Context, url: String) {
                                        try {
                                            val uri = Uri.parse(url)
                                            val st = uri.getQueryParameter("ST")
                                            if (!st.isNullOrBlank()) {
                                                BattleNetSignIn.saveWebToken(context, st)
                                                hasCapturedToken = true
                                                SnackbarManager.show("已捕获并保存国服战网 SSO Token！")
                                            }
                                        } catch (_: Exception) {}
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
                    Text("完成网页验证 / 返回启动器", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
