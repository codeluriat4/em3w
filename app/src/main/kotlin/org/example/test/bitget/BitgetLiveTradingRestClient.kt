package org.example.test.bitget

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Talks to Bitget's v2 mix (futures) trading endpoints against the **real**
 * matching engine: https://www.bitget.com/api-doc/contract/trade/Place-Order
 *
 * This is deliberately a near-mirror of [BitgetPaperTradingRestClient] with
 * one load-bearing difference: it never sends the `paptrading: 1` header, so
 * every request here can place, close, and report on positions funded with
 * real money. Keep that in mind before touching this file - a stray copy of
 * the demo header would silently route live orders into the sandbox, and
 * removing it from the demo client would silently send paper orders to the
 * real exchange.
 */
class BitgetLiveTradingRestClient(
    private val credentialsProvider: () -> BitgetCredentials?,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private companion object {
        const val TAG = "BitgetLiveTrading"
        const val BASE_URL = "https://api.bitget.com"
        const val PRODUCT_TYPE = "USDT-FUTURES"
        const val JSON_MEDIA_TYPE = "application/json"
    }

    class NotAuthenticatedException : IOException("No Bitget live API Key configured")
    class BitgetApiException(val code: String, message: String) : IOException(message)

    suspend fun fetchAllPositions(): List<PaperPosition> {
        val path = "/api/v2/mix/position/all-position"
        val query = "productType=$PRODUCT_TYPE&marginCoin=USDT"
        val json = get(path, query)
        val rows = json.optJSONArray("data") ?: JSONArray()
        return buildList(rows.length()) {
            for (i in 0 until rows.length()) {
                parsePosition(rows.getJSONObject(i))?.let { add(it) }
            }
        }
    }

    suspend fun fetchAccountBalance(): PaperAccountBalance? {
        val path = "/api/v2/mix/account/accounts"
        val query = "productType=$PRODUCT_TYPE"
        val json = get(path, query)
        val rows = json.optJSONArray("data") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("marginCoin").equals("USDT", ignoreCase = true)) {
                return PaperAccountBalance(
                    marginCoin = row.optString("marginCoin", "USDT"),
                    available = row.optString("available", "0").toDoubleOrNull() ?: 0.0,
                    equity = row.optString("usdtEquity", "0").toDoubleOrNull() ?: 0.0,
                    unrealizedPnl = row.optString("unrealizedPL", "0").toDoubleOrNull() ?: 0.0,
                )
            }
        }
        return null
    }

    suspend fun setLeverage(symbol: String, leverage: Int, marginMode: String = "crossed") {
        val path = "/api/v2/mix/account/set-leverage"
        val body = JSONObject().apply {
            put("symbol", symbol)
            put("productType", PRODUCT_TYPE)
            put("marginCoin", "USDT")
            put("leverage", leverage.toString())
        }
        // Best-effort: some accounts reject redundant leverage changes; that's fine, ignore.
        runCatching { post(path, body) }
    }

    /** Opens or adds to a position in [ticket.side] with a market order, funded with real balance. */
    suspend fun openPosition(ticket: OrderTicket): PlacedOrder {
        val clientOrderId = "live-${System.currentTimeMillis()}"
        val body = JSONObject().apply {
            put("symbol", ticket.symbol)
            put("productType", PRODUCT_TYPE)
            put("marginMode", ticket.marginMode)
            put("marginCoin", "USDT")
            put("size", ticket.sizeInBaseCoin)
            put("side", ticket.side.openOrderSide)
            put("tradeSide", "open") // ignored by Bitget in one-way mode, required in hedge mode
            put("orderType", "market")
            put("clientOid", clientOrderId)
        }
        val json = post("/api/v2/mix/order/place-order", body)
        val data = json.getJSONObject("data")
        return PlacedOrder(
            orderId = data.optString("orderId"),
            clientOrderId = data.optString("clientOid", clientOrderId),
        )
    }

    /** Closes (all or part of) an existing position with a reduce-only market order. */
    suspend fun closePosition(symbol: String, side: PositionSide, sizeInBaseCoin: String, marginMode: String = "crossed"): PlacedOrder {
        val clientOrderId = "live-close-${System.currentTimeMillis()}"
        val body = JSONObject().apply {
            put("symbol", symbol)
            put("productType", PRODUCT_TYPE)
            put("marginMode", marginMode)
            put("marginCoin", "USDT")
            put("size", sizeInBaseCoin)
            put("side", side.closeOrderSide)
            put("tradeSide", "close")
            put("orderType", "market")
            put("reduceOnly", "YES")
            put("clientOid", clientOrderId)
        }
        val json = post("/api/v2/mix/order/place-order", body)
        val data = json.getJSONObject("data")
        return PlacedOrder(
            orderId = data.optString("orderId"),
            clientOrderId = data.optString("clientOid", clientOrderId),
        )
    }

    private fun parsePosition(row: JSONObject): PaperPosition? {
        val total = row.optString("total", "0").toDoubleOrNull() ?: 0.0
        if (total <= 0.0) return null // Bitget returns zeroed rows for closed sides; skip them.
        return PaperPosition(
            symbol = row.optString("symbol"),
            side = PositionSide.fromHoldSide(row.optString("holdSide")),
            total = total,
            available = row.optString("available", "0").toDoubleOrNull() ?: 0.0,
            entryPrice = row.optString("openPriceAvg", "0").toDoubleOrNull() ?: 0.0,
            markPrice = row.optString("markPrice", "0").toDoubleOrNull() ?: 0.0,
            leverage = row.optString("leverage", "1").toDoubleOrNull()?.toInt() ?: 1,
            marginSize = row.optString("marginSize", "0").toDoubleOrNull() ?: 0.0,
            unrealizedPnl = row.optString("unrealizedPL", "0").toDoubleOrNull() ?: 0.0,
        )
    }

    private suspend fun get(path: String, query: String): JSONObject {
        val credentials = credentialsProvider() ?: throw NotAuthenticatedException()
        val timestamp = System.currentTimeMillis().toString()
        val requestPath = if (query.isBlank()) path else "$path?$query"
        val sign = BitgetRequestSigner.sign(credentials.secretKey, timestamp, "GET", requestPath, "")

        val request = Request.Builder()
            .url("$BASE_URL$requestPath")
            .get()
            .applyAuthHeaders(credentials, timestamp, sign)
            .build()

        return parseResponse(executeAsync(request))
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject {
        val credentials = credentialsProvider() ?: throw NotAuthenticatedException()
        val timestamp = System.currentTimeMillis().toString()
        val bodyString = body.toString()
        val sign = BitgetRequestSigner.sign(credentials.secretKey, timestamp, "POST", path, bodyString)

        val request = Request.Builder()
            .url("$BASE_URL$path")
            .post(bodyString.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .applyAuthHeaders(credentials, timestamp, sign)
            .build()

        return parseResponse(executeAsync(request))
    }

    private fun Request.Builder.applyAuthHeaders(credentials: BitgetCredentials, timestamp: String, sign: String): Request.Builder {
        val builder = header("ACCESS-KEY", credentials.apiKey)
            .header("ACCESS-SIGN", sign)
            .header("ACCESS-TIMESTAMP", timestamp)
            .header("ACCESS-PASSPHRASE", credentials.passphrase)
            .header("Content-Type", JSON_MEDIA_TYPE)
            .header("locale", "en-US")
        // Only added when the key is explicitly flagged as a Testnet/demo
        // key via the "Environment" selector - routes the very same signed
        // request at Bitget's sandbox matching engine instead of the real
        // one. Omitted entirely for Mainnet keys, matching the class doc.
        return if (credentials.isTestnet) builder.header("paptrading", "1") else builder
    }

    private fun parseResponse(body: String): JSONObject {
        val json = JSONObject(body)
        val code = json.optString("code")
        if (code != "00000") {
            throw BitgetApiException(code, json.optString("msg", "Unknown Bitget error"))
        }
        return json
    }

    private suspend fun executeAsync(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "Live trading request failed: ${e.message}")
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string()
                        if (text == null) {
                            continuation.resumeWithException(IOException("Empty response body (HTTP ${it.code})"))
                        } else {
                            // Bitget returns 200 with an error `code` in the JSON payload for most
                            // API errors, so we parse the body even on non-2xx HTTP responses.
                            continuation.resume(text)
                        }
                    }
                }
            })
        }
}
