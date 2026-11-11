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
}
