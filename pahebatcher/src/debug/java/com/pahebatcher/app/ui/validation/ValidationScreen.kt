package com.pahebatcher.app.ui.validation

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pahebatcher.app.data.remote.validation.OkHttpValidator
import com.pahebatcher.app.data.remote.validation.KwikValidator
import com.pahebatcher.app.data.remote.validation.WebViewSessionValidator
import kotlinx.coroutines.launch

/**
 * Temporary developer-only validation screen.
 * Exposes Phase 5 validation spike for real-device testing.
 *
 * DO NOT use in production.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValidationScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isRunning by remember { mutableStateOf(false) }
    var webViewResult by remember { mutableStateOf<WebViewSessionValidator.ValidationResult?>(null) }
    var httpResult by remember { mutableStateOf<OkHttpValidator.HttpRequestResult?>(null) }
    var kwikResult by remember { mutableStateOf<KwikValidator.KwikResult?>(null) }
    var logMessages by remember { mutableStateOf(listOf<String>()) }
    var phase by remember { mutableStateOf("Idle") }

    fun addLog(message: String) {
        logMessages = logMessages + message
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phase 5 Validation (Dev Only)") },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Status: $phase",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (isRunning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(16.dp)
                            )
                            Text(
                                text = " Running...",
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }

            // Run button
            Button(
                onClick = {
                    scope.launch {
                        isRunning = true
                        logMessages = emptyList()
                        webViewResult = null
                        httpResult = null
                        kwikResult = null

                        try {
                            // Phase 1: WebView validation
                            phase = "Phase 1: WebView Session"
                            addLog("=== Starting WebView Validation ===")
                            val webViewValidator = WebViewSessionValidator(context)
                            val wvResult = webViewValidator.validate()
                            webViewResult = wvResult
                            addLog("Page loaded: ${wvResult.pageLoaded}")
                            addLog("CF detected: ${wvResult.cloudflareChallengeDetected}")
                            addLog("CF completed: ${wvResult.cloudflareChallengeCompleted}")
                            addLog("AnimePahe accessible: ${wvResult.animePaheAccessible}")
                            addLog("Cookies: ${wvResult.cookies.keys}")
                            addLog("User-Agent: ${wvResult.userAgent.take(50)}...")
                            addLog("Final URL: ${wvResult.currentUrl}")
                            addLog("Success: ${wvResult.success}")
                            wvResult.diagnosticMessages.forEach { addLog("  $it") }

                            // OkHttp validation with transferred cookies
                            phase = "Phase 2: OkHttp Session"
                            addLog("\n=== Starting OkHttp Validation ===")
                            addLog("--- WebView vs OkHttp Comparison ---")
                            addLog("WebView final URL: ${wvResult.currentUrl}")
                            addLog("WebView User-Agent: ${wvResult.userAgent}")
                            addLog("OkHttp request URL: https://animepahe.pw")
                            addLog("OkHttp User-Agent: ${wvResult.userAgent}")
                            addLog("Cookies transferred: ${wvResult.cookies.size}")
                            addLog("Cookie names: ${wvResult.cookies.keys}")
                            
                            val httpValidator = OkHttpValidator()

                            // Transfer cookies from WebView
                            if (wvResult.cookies.isNotEmpty()) {
                                addLog("Importing ${wvResult.cookies.size} cookies from WebView")
                                addLog("Cookie names: ${wvResult.cookies.keys}")
                                // Import cookies for BOTH domains (site redirects between them)
                                httpValidator.importCookies(wvResult.cookies, "animepahe.com")
                                httpValidator.importCookies(wvResult.cookies, "animepahe.pw")
                                addLog("Cookies imported to domains: animepahe.com, animepahe.pw")
                            }

                            // Test basic request using the WebView's User-Agent for consistency
                            val hResult = httpValidator.testGetRequest(
                                url = "https://animepahe.pw",
                                referer = "https://animepahe.pw/",
                                userAgent = wvResult.userAgent
                            )
                            
                            // Log cookie details for debugging
                            addLog("Cookies by domain: ${httpValidator.getCookieNamesByDomain()}")
                            httpResult = hResult
                            addLog("\n--- OkHttp Response Summary ---")
                            addLog("Status code: ${hResult.statusCode}")
                            addLog("Protocol: ${hResult.protocol ?: "unknown"}")
                            addLog("Final URL: ${hResult.finalUrl}")
                            addLog("Body length: ${hResult.bodyLength}")
                            addLog("Success: ${hResult.success}")
                            addLog("Cloudflare response: ${hResult.isCloudflareResponse}")
                            addLog("AnimePahe response: ${hResult.isAnimePaheResponse}")
                            addLog("Content-Type: ${hResult.contentType ?: "none"}")
                            addLog("Server: ${hResult.serverHeader ?: "none"}")
                            addLog("CF-Ray: ${hResult.cfRay ?: "none"}")
                            hResult.diagnosticMessages.forEach { addLog("  $it") }

                            // Kwik validation (with placeholder URL for now)
                            phase = "Phase 3: Kwik Resolution"
                            addLog("\n=== Starting Kwik Validation ===")
                            addLog("Kwik validation requires a real Kwik URL from AnimePahe")
                            addLog("Use OkHttp result above to verify session transfer works")

                            phase = "Complete"
                            addLog("\n=== Validation Complete ===")

                        } catch (e: Exception) {
                            addLog("ERROR: ${e.message}")
                            phase = "Failed: ${e.message}"
                        } finally {
                            isRunning = false
                        }
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Run Validation")
            }

            // WebView result
            webViewResult?.let { result ->
                ResultCard(
                    title = "WebView Result",
                    success = result.success,
                    details = listOf(
                        "Page loaded: ${result.pageLoaded}",
                        "CF detected: ${result.cloudflareChallengeDetected}",
                        "CF completed: ${result.cloudflareChallengeCompleted}",
                        "Accessible: ${result.animePaheAccessible}",
                        "Cookies: ${result.cookies.size}",
                        "Title: ${result.pageTitle}"
                    )
                )
            }

            // HTTP result
            httpResult?.let { result ->
                ResultCard(
                    title = "OkHttp Result",
                    success = result.success,
                    details = listOf(
                        "Status: ${result.statusCode}",
                        "Protocol: ${result.protocol ?: "unknown"}",
                        "Final URL: ${result.finalUrl}",
                        "Body: ${result.bodyLength} bytes",
                        "Cookies: ${result.cookies.size}",
                        "Redirects: ${result.redirectCount}",
                        "Content-Type: ${result.contentType ?: "none"}",
                        "Server: ${result.serverHeader ?: "none"}",
                        "CF-Ray: ${result.cfRay ?: "none"}",
                        "CF-Cache-Status: ${result.cfCacheStatus ?: "none"}",
                        "Cloudflare response: ${result.isCloudflareResponse}",
                        "AnimePahe response: ${result.isAnimePaheResponse}"
                    )
                )
            }

            // Kwik result
            kwikResult?.let { result ->
                ResultCard(
                    title = "Kwik Result",
                    success = result.success,
                    details = listOf(
                        "M3U8: ${result.m3u8Url ?: "None"}",
                        "Status: ${result.statusCode}",
                        "TLS blocked: ${result.tlsFingerprintBlocked}"
                    )
                )
            }

            // Log output
            if (logMessages.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Diagnostic Log",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Log text expands naturally within the outer scrolling Column
                        Text(
                            text = logMessages.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    title: String,
    success: Boolean,
    details: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (success)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "$title: ${if (success) "SUCCESS" else "FAILED"}",
                style = MaterialTheme.typography.titleSmall,
                color = if (success)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            details.forEach { detail ->
                Text(
                    text = "• $detail",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
