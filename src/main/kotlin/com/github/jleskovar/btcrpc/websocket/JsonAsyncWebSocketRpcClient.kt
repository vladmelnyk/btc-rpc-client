package com.github.jleskovar.btcrpc.websocket

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.CompletableFuture
import javax.net.ssl.SSLContext

class JsonAsyncWebSocketRpcClient(wsUrl: String, sslContext: SSLContext) : AbstractJsonWebSocketRpcClient(wsUrl, sslContext) {
    private val responses = mutableMapOf<String, Pair<Type, CompletableFuture<Any>>>()

    override fun handleTextMessage(text: String?) {
        val id = fastExtractId(text!!)
        val future = responses[id]?.second
        if (hasError(text)) {
            val error = extractError(text)
            future?.completeExceptionally(JsonRpcError(error.first, error.second))
        } else {
            val returnType = responses[id]?.first
            try {
                val responseValue = super.readResponse(returnType, ByteArrayInputStream(text.toByteArray()))
                future?.complete(responseValue)
            } catch (ex: Exception) {
                future?.completeExceptionally(ex)
            }
        }
        responses.remove(id)
    }

    override fun invoke(methodName: String?, argument: Any?, returnType: Type?, extraHeaders: MutableMap<String, String>?): Any? {
        val outputStream = ByteArrayOutputStream()
        super.invoke(methodName, argument, outputStream)
        val output = outputStream.toString()

        val id = fastExtractId(output)

        val realReturnType = when(returnType) {
            is ParameterizedType -> returnType.actualTypeArguments[0] // Dig out "T" from a "CompletableFuture<T>"
            else -> returnType
        }

        val completableFuture = CompletableFuture<Any>()
        responses[id] = Pair(realReturnType!!, completableFuture)
        socket.sendText(output)
        return completableFuture
    }
}