package com.wmspro.common.exception

class BusinessException(
    message: String,
    val errorCode: String? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)