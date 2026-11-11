package com.nuvio.tv.data.floppy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal class FloppyTestPersistence : FloppyAuthPersistence {
    val profiles = mutableMapOf<Int, String>()
    var installId: String? = null
    override fun read(profileId: Int): String? = profiles[profileId]
    override fun write(profileId: Int, value: String?) {
        if (value == null) profiles.remove(profileId) else profiles[profileId] = value
    }
    override fun clear() = profiles.clear()
    override fun readInstallId(): String? = installId
    override fun writeInstallId(value: String) {
        installId = value
    }
}

internal class RecordedFloppyRequest(
    val request: FloppyHttpRequest,
    val bearer: String?
) {
    val path: String get() = request.url.substringAfter("://").substringAfter('/', "").let { "/$it" }
    val form: Map<String, String>? get() = request.form
    val body: JsonObject get() = Json.parseToJsonElement(request.json!!).jsonObject
    val headers: Map<String, String> get() = request.headers
}

internal class FloppyTestEngine : FloppyHttpEngine {
    val requests = mutableListOf<RecordedFloppyRequest>()
    var handler: suspend (RecordedFloppyRequest) -> FloppyHttpResponse = { FloppyHttpResponse(200, "{}") }

    fun requestsTo(path: String) = requests.filter { it.path == path }

    override suspend fun execute(request: FloppyHttpRequest, bearer: String?): FloppyHttpResponse {
        val recorded = RecordedFloppyRequest(request, bearer)
        requests += recorded
        return handler(recorded)
    }
}

internal const val TEST_SERVER = "https://floppy.example.com"

internal fun infoBody(vararg missing: String): String {
    val extensions = FLOPPY_REQUIRED_EXTENSIONS + "sync_items_v1" - missing.toSet()
    return """{"version":"9.9.9","api_extensions":{${extensions.joinToString(",") { "\"$it\":true" }},"search_id_v1":false}}"""
}

internal fun tokenBody(access: String, refresh: String?, expiresIn: Long = 604_800L): String =
    buildString {
        append("""{"access_token":"$access",""")
        if (refresh != null) append(""""refresh_token":"$refresh",""")
        append(""""token_type":"Bearer","expires_in":$expiresIn,"scope":"scrobble:write sync:read"}""")
    }

internal fun deviceBody(
    verification: String = "$TEST_SERVER/o/device/",
    complete: String = "$TEST_SERVER/o/device/?user_code=ABCD-EFGH"
): String =
    """{"device_code":"dc-secret","user_code":"ABCD-EFGH","verification_uri":"$verification","verification_uri_complete":"$complete","expires_in":900,"interval":5}"""

internal class FloppyTestHarness(var now: Long = 1_000_000L) {
    val persistence = FloppyTestPersistence()
    val engine = FloppyTestEngine()
    val store = FloppyAuthStore(persistence)
    val http = FloppyHttpClient(engine)
    val auth = FloppyAuthRepository(http, store) { now }
    val api = FloppyApiClient(http, auth)

    /** Stores a signed-in connection directly, without going through the device flow. */
    fun connect(
        access: String = "access-one",
        refresh: String = "refresh-one",
        expiresAtEpochMs: Long = now + 7L * 24 * 3_600_000L
    ) {
        val server = FloppyServerAddress.parse(TEST_SERVER)!!
        val session = FloppyDeviceSession("CODE", "$TEST_SERVER/o/device/", "$TEST_SERVER/o/device/", now + 900_000L, 5, now)
        check(store.saveSession(server, FLOPPY_REQUIRED_EXTENSIONS, session, "dc", store.scope()))
        check(store.authorize(FloppyTokens(access, refresh, expiresAtEpochMs), store.scope()))
    }
}

internal suspend inline fun <reified T : Throwable> expectFloppyFailure(block: () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw AssertionError("Expected ${T::class.simpleName}, got ${error::class.simpleName}", error)
    }
    throw AssertionError("Expected ${T::class.simpleName}")
}
