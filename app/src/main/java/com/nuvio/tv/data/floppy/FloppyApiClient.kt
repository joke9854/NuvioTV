package com.nuvio.tv.data.floppy

import kotlinx.serialization.json.JsonObject

/** Authenticated JSON calls to the configured Floppy server. */
class FloppyApiClient(
    private val http: FloppyHttpClient,
    private val auth: FloppyAuthRepository
) {
    /**
     * Posts [body] with the stored credentials, refreshing once on a 401. The body and headers are fixed
     * before the call, so the retry carries exactly the same event ids.
     */
    suspend fun post(
        scope: FloppyAuthScope,
        path: String,
        body: JsonObject,
        headers: Map<String, String> = emptyMap()
    ): FloppyHttpResponse {
        val payload = body.toString()
        return auth.withAuthorization(scope) { authorization ->
            http.execute(
                FloppyHttpRequest(
                    method = FloppyHttpMethod.POST,
                    url = authorization.server.url(path),
                    json = payload,
                    bearer = authorization.tokens.accessToken,
                    headers = headers
                ),
                authorization.server
            ).requireSuccess()
        }
    }

    /**
     * Reads [path] (which may carry a query string). A 409 is returned instead of thrown when
     * [returnConflict] is set, so the caller can read its machine-readable code.
     */
    suspend fun get(
        scope: FloppyAuthScope,
        path: String,
        returnConflict: Boolean = false
    ): FloppyHttpResponse = auth.withAuthorization(scope) { authorization ->
        val response = http.execute(
            FloppyHttpRequest(
                method = FloppyHttpMethod.GET,
                url = authorization.server.url(path),
                bearer = authorization.tokens.accessToken
            ),
            authorization.server
        )
        if (returnConflict && response.status == 409) response else response.requireSuccess()
    }

    /** Sends a DELETE with a JSON [body]. */
    suspend fun delete(scope: FloppyAuthScope, path: String, body: JsonObject): FloppyHttpResponse {
        val payload = body.toString()
        return auth.withAuthorization(scope) { authorization ->
            http.execute(
                FloppyHttpRequest(
                    method = FloppyHttpMethod.DELETE,
                    url = authorization.server.url(path),
                    json = payload,
                    bearer = authorization.tokens.accessToken
                ),
                authorization.server
            ).requireSuccess()
        }
    }
}
