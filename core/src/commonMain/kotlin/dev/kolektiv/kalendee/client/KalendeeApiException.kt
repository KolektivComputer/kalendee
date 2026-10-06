package dev.kolektiv.kalendee.client

class KalendeeApiException(
    val status: Int,
    val code: String?,
    override val message: String,
) : Exception(message) {
    val isUnauthorized: Boolean get() = status == 401

    val isPreconditionFailed: Boolean get() = status == 412
}
