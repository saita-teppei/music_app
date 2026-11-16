package com.saitateppei.opencloudflac.data.webdav

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class ServerConfig(val serverId: Long, val baseUrl: String, val username: String, val token: String)

/** WebDAVの失敗。画面とワーカーはこの種類で挙動を変える(6.4)。 */
sealed class DavException(message: String) : IOException(message) {
    class Unreachable(cause: Throwable?) : DavException("サーバーに届きません。tailnetに接続していますか") {
        init { if (cause != null) initCause(cause) }
    }
    class Unauthorized(code: Int) : DavException("認証に失敗しました($code)。トークンを確認してください")
    class NotFound : DavException("サーバー上にもうありません(404)")
    class Http(val code: Int) : DavException("サーバーエラー($code)")
}

/**
 * OpenCloudへのWebDAVアクセス。読み取り(PROPFIND/GET)だけを実装する。
 * アップロード・削除・移動は共有データを守るため実装しない(8章)。
 */
class WebDavClient(private val baseClient: OkHttpClient) {

    fun list(cfg: ServerConfig, path: String, depth: Int = 1): List<DavEntry> {
        val url = urlFor(cfg.baseUrl, path)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(cfg.username, cfg.token))
            .header("Depth", depth.toString())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        val response = execute(request)
        response.use {
            checkStatus(it)
            val body = it.body ?: throw DavException.Http(it.code)
            return PropfindParser.parse(body.byteStream(), cfg.baseUrl.toHttpUrl().encodedPath)
        }
    }

    /** 1件だけの情報(変更確認・接続テスト用) */
    fun stat(cfg: ServerConfig, path: String): DavEntry? = list(cfg, path, depth = 0).firstOrNull()

    /**
     * ファイルを開く。[rangeStart] > 0 なら Range を付け、[ifRangeEtag] で変更検知する。
     * ETagが変わっていればサーバーは 200 で全体を返すので、呼び出し側は最初から書き直す。
     */
    fun open(cfg: ServerConfig, path: String, rangeStart: Long = 0, ifRangeEtag: String? = null): Response {
        val builder = Request.Builder()
            .url(urlFor(cfg.baseUrl, path))
            .header("Authorization", Credentials.basic(cfg.username, cfg.token))
            .get()
        if (rangeStart > 0) {
            builder.header("Range", "bytes=$rangeStart-")
            if (ifRangeEtag != null) builder.header("If-Range", ifRangeEtag)
        }
        val response = execute(builder.build(), downloadClient)
        if (response.code == 416) return response
        try {
            checkStatus(response)
        } catch (e: Exception) {
            response.close(); throw e
        }
        return response
    }

    private val downloadClient: OkHttpClient by lazy {
        baseClient.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun execute(request: Request, client: OkHttpClient = baseClient): Response = try {
        client.newCall(request).execute()
    } catch (e: UnknownHostException) {
        throw DavException.Unreachable(e)
    } catch (e: ConnectException) {
        throw DavException.Unreachable(e)
    } catch (e: NoRouteToHostException) {
        throw DavException.Unreachable(e)
    } catch (e: SocketTimeoutException) {
        throw DavException.Unreachable(e)
    }

    private fun checkStatus(r: Response) {
        when {
            r.code == 401 || r.code == 403 -> throw DavException.Unauthorized(r.code)
            r.code == 404 -> throw DavException.NotFound()
            !r.isSuccessful -> throw DavException.Http(r.code)
        }
    }

    companion object {
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getetag/><d:getlastmodified/></d:prop></d:propfind>"""

        /** ベースURL + 相対パス。パスはセグメントごとにエンコードする。 */
        fun urlFor(baseUrl: String, path: String): HttpUrl {
            val base = baseUrl.trimEnd('/').toHttpUrl()
            val b = base.newBuilder()
            path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
            if (path.endsWith("/") && path.length > 1) b.addPathSegment("")
            return b.build()
        }

        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
